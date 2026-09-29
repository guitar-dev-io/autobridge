package dev.autobridge.browser

/** Plain rectangle so chrome geometry stays unit-testable without an Android framework stub. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
}

/** A tap target in the browser chrome. */
enum class ChromeZone {
    NONE,
    BACK, FORWARD, RELOAD, ADDRESS, FULLSCREEN, MENU,
    /** Empty space on the visible toolbar. Consumed so the tap cannot reach the page beneath it. */
    TOOLBAR_BACKGROUND,
    /** Slim strip that recalls the chrome while it is hidden. */
    HANDLE,
    /** Narrow edge band that reveals auto-hidden chrome without stealing page taps. */
    EDGE_REVEAL,
    /** Anywhere over an open drawer that is not an item — closes it. */
    DRAWER_SCRIM,
    /**
     * The floating control button. Unlike [MENU] it does not live on the auto-hiding toolbar, so
     * the menu is always one tap away instead of two (recall the chrome, then hit a toolbar icon).
     */
    FAB,
}

/** One toolbar button: where it can be tapped, and how large its glyph is drawn. */
data class ChromeSlot(val zone: ChromeZone, val bounds: Box, val iconSize: Float)

/**
 * Resolves the browser toolbar into concrete rectangles, once, from [AutoUiSizes] and the viewport.
 *
 * This is the single source of truth for both drawing and hit testing. They used to be two
 * independent sets of literals in [CarWebRenderer] (`BACK_ZONE_WIDTH * 2`, `w - 34f`, ...), which
 * meant a change to one silently desynchronised the other and buttons stopped landing where they
 * were drawn.
 *
 * Being pure data with no Android dependency, the layout can be asserted directly at every head
 * unit resolution in a JVM test.
 */
class BrowserChromeLayout private constructor(
    val sizes: AutoUiSizes,
    val viewport: BrowserViewport,
    val toolbar: Box,
    val slots: List<ChromeSlot>,
    val address: Box,
    val handle: Box,
    val edgeReveal: Box,
    val fab: Box,
) {
    companion object {
        fun create(sizes: AutoUiSizes, viewport: BrowserViewport): BrowserChromeLayout {
            val left = viewport.left.toFloat()
            val top = viewport.top.toFloat()
            val right = left + viewport.width
            val barHeight = sizes.toolbarHeight(viewport.height)
            val toolbar = Box(left, top, right, top + barHeight)

            // A button is never narrower than the touch target, and never wider than it needs to be.
            val target = sizes.touchTarget.coerceAtMost(viewport.width / 8f)
            val icon = sizes.iconMedium.coerceAtMost(barHeight * 0.5f)
            val pad = sizes.horizontalPadding

            val leading = listOf(ChromeZone.BACK, ChromeZone.FORWARD, ChromeZone.RELOAD)
            val trailing = listOf(ChromeZone.FULLSCREEN, ChromeZone.MENU)

            val slots = ArrayList<ChromeSlot>(leading.size + trailing.size)
            var cursor = left + pad
            leading.forEach { zone ->
                slots += ChromeSlot(zone, Box(cursor, top, cursor + target, top + barHeight), icon)
                cursor += target
            }
            val addressStart = cursor + sizes.contentGap

            var trailingCursor = right - pad - target * trailing.size
            val addressEnd = trailingCursor - sizes.contentGap
            trailing.forEach { zone ->
                slots += ChromeSlot(
                    zone,
                    Box(trailingCursor, top, trailingCursor + target, top + barHeight),
                    icon
                )
                trailingCursor += target
            }

            // The address pill absorbs whatever is left; it collapses rather than overlapping the
            // buttons when a very narrow panel cannot fit a comfortable width.
            val addressBox = Box(
                addressStart,
                top + sizes.contentGap * 0.75f,
                addressEnd.coerceAtLeast(addressStart),
                top + barHeight - sizes.contentGap * 0.75f
            )

            val handleHeight = sizes.handleHeight
            val handleWidth = (viewport.width * 0.22f).coerceAtLeast(sizes.touchTarget * 2f)
            val handle = Box(
                left + (viewport.width - handleWidth) / 2f, top,
                left + (viewport.width + handleWidth) / 2f, top + handleHeight
            )

            // Deliberately a narrow band, not a full-surface gesture layer: anything wider would
            // intercept taps meant for the page.
            val edgeReveal = Box(left, top, right, top + sizes.edgeReveal)

            // Bottom-trailing corner: far from the toolbar it duplicates, and the part of the page
            // least likely to hold a control the user meant to press. Bounded against the surface
            // so it cannot dominate a short panel.
            val bottom = top + viewport.height
            val fabSize = sizes.fabSize.coerceAtMost(minOf(viewport.width, viewport.height) * 0.22f)
            val fabMargin = sizes.fabMargin
            val fab = Box(
                right - fabMargin - fabSize, bottom - fabMargin - fabSize,
                right - fabMargin, bottom - fabMargin
            )

            return BrowserChromeLayout(
                sizes, viewport, toolbar, slots, addressBox, handle, edgeReveal, fab
            )
        }
    }

    /**
     * Classifies a surface tap.
     *
     * @param chromeVisible whether the toolbar is currently shown.
     * @param drawer the open drawer's geometry, or null when closed.
     */
    fun hitTest(x: Float, y: Float, chromeVisible: Boolean, drawer: Box?): ChromeZone {
        if (!viewport.contains(x, y)) return ChromeZone.NONE
        if (drawer != null) {
            // An open drawer owns every tap: items are resolved by the drawer itself, and anything
            // outside it dismisses. The page underneath never sees these taps.
            return if (drawer.contains(x, y)) ChromeZone.NONE else ChromeZone.DRAWER_SCRIM
        }
        // Checked before the toolbar and before the page: the floating button is the one control
        // that is available in every chrome state, which is the whole reason it exists.
        if (fab.contains(x, y)) return ChromeZone.FAB
        if (chromeVisible) {
            slots.firstOrNull { it.bounds.contains(x, y) }?.let { return it.zone }
            if (toolbar.contains(x, y)) {
                // The toolbar is opaque chrome: its gaps belong to it, not to the page underneath.
                return if (address.contains(x, y)) ChromeZone.ADDRESS else ChromeZone.TOOLBAR_BACKGROUND
            }
            return ChromeZone.NONE
        }
        if (handle.contains(x, y)) return ChromeZone.HANDLE
        if (edgeReveal.contains(x, y)) return ChromeZone.EDGE_REVEAL
        return ChromeZone.NONE
    }

    fun slot(zone: ChromeZone): ChromeSlot? = slots.firstOrNull { it.zone == zone }
}

/**
 * Bounds for page scrolling, kept pure so the clamp itself is covered by a test.
 *
 * The renderer previously called `View.scrollBy`, which applies a delta with no bounds at all. The
 * offset then walked outside the content and stayed there, which reads to the user as scrolling
 * that has jammed and leaves the page drawn at an offset it can never recover from.
 */
object PageScroll {
    /** Applies [delta] to [current] and confines the result to `0..max`. */
    fun clamp(current: Int, delta: Int, max: Int): Int =
        (current + delta).coerceIn(0, max.coerceAtLeast(0))
}

/**
 * Compact, human-readable form of a URL for list rows.
 *
 * A bookmarked search result can carry a query string thousands of characters long. Passed straight
 * to a car `Row`, it rendered as a wall of text that filled the whole screen and buried every other
 * entry, so what a row shows is deliberately bounded here rather than at each call site.
 */
object BrowserDisplayUrl {
    const val DEFAULT_MAX = 64

    fun compact(url: String, max: Int = DEFAULT_MAX): String {
        val limit = max.coerceAtLeast(8)
        val withoutScheme = url
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
        // Query strings carry the bulk of the length and none of the meaning at a glance.
        val head = withoutScheme.substringBefore('?').substringBefore('#')
        val trimmed = head.trimEnd('/').ifEmpty { withoutScheme.take(limit) }
        val hadMore = head.length < withoutScheme.length
        return when {
            trimmed.length > limit -> trimmed.take(limit - 1) + "…"
            hadMore -> "$trimmed…"
            else -> trimmed
        }
    }
}
