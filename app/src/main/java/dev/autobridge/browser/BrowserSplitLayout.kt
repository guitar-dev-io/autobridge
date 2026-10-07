package dev.autobridge.browser

import android.content.Context
import androidx.annotation.StringRes
import androidx.core.content.edit
import dev.autobridge.R
import kotlin.math.roundToInt

/**
 * How the car browser divides its surface between the main page and an optional side page.
 *
 * The main page is the full browser (tabs, toolbar, history); the side page is a second, simpler
 * page — typically a map — shown next to it. Fractions are the side pane's share of the width.
 */
enum class BrowserSplitLayout(@StringRes val labelRes: Int, val glyph: String) {
    SINGLE(R.string.split_single, "▭"),
    HALF(R.string.split_half, "◫"),
    FORTY_SIXTY(R.string.split_forty_sixty, "◧"),

    /**
     * The main pane takes 65% (video / web), the side pane 35% (map / navigation / info). This is
     * the "video beside a map" preset: wide enough for a 16:9-ish main picture while still leaving a
     * usable navigation strip, without pinning either pane to a fixed aspect the way
     * [PORTRAIT_LANDSCAPE] does.
     */
    SIXTY_FIVE_THIRTY_FIVE(R.string.split_sixty_five_thirty_five, "◧"),

    /**
     * A tall side pane next to a 16:9 main pane: e.g. a portrait map beside a landscape video. The
     * main pane is sized to exactly 16:9 where the surface allows, the side pane takes the rest.
     */
    PORTRAIT_LANDSCAPE(R.string.split_portrait_landscape, "▯▭");

    fun next(): BrowserSplitLayout = entries[(ordinal + 1) % entries.size]

    /** The name to show for this layout; a string id for the reason [FloatingButtonAction] gives. */
    fun label(context: Context): String = context.getString(labelRes)
}

/** A pane rectangle in car-surface pixels. Plain ints so the geometry is testable on the JVM. */
data class PaneRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    fun contains(x: Float, y: Float): Boolean = x >= left && y >= top && x < right && y < bottom
}

/**
 * The two panes of a split. [main] holds the full browser, [side] the second page.
 *
 * [stacked] is a split on a portrait display: the panes sit one above the other rather than side
 * by side, and "side on the right" means "side page at the bottom".
 */
data class SplitPanes(val main: PaneRect, val side: PaneRect, val stacked: Boolean = false)

object BrowserSplitGeometry {
    /**
     * How far a dragged divider may go, as the side pane's share of the usable width. The real
     * limit is usually [panes]' `minPanePx` — these only stop a very wide panel from being dragged
     * to a ratio that is technically legible but useless.
     */
    const val MIN_SIDE_FRACTION = 0.2f
    const val MAX_SIDE_FRACTION = 0.8f

    /** Side share of the width for the fixed-ratio layouts. */
    private const val HALF_FRACTION = 0.5f
    private const val FORTY_FRACTION = 0.4f

    /** Side (map) share for the 65/35 preset: the main pane keeps 65%, the map strip takes 35%. */
    private const val THIRTY_FIVE_FRACTION = 0.35f

    /**
     * How much taller than wide a panel must be before the panes stack. A nearly square surface
     * is left to the side-by-side rules (and to staying single when too narrow for them).
     */
    private const val PORTRAIT_RATIO = 1.2f

    /** In [BrowserSplitLayout.PORTRAIT_LANDSCAPE], the 16:9 main pane never takes more than this. */
    private const val LANDSCAPE_MAX_FRACTION = 0.7f

    /**
     * Splits the card rect into two panes, or returns null when [layout] is
     * [BrowserSplitLayout.SINGLE] or either pane would be narrower than [minPanePx] (a split
     * nobody can use is worse than no split).
     *
     * [gapPx] is left empty between the panes for the divider. [sideOnRight] mirrors the layout so
     * the side pane can sit on either side of the driver.
     *
     * [sideFraction] is a divider the user has dragged: it overrides the preset's own ratio (and,
     * for [BrowserSplitLayout.PORTRAIT_LANDSCAPE], its 16:9 main pane, which is a fixed shape and
     * so has nothing left to drag). Cycling the preset clears it — see [BrowserSplitStore].
     */
    fun panes(
        layout: BrowserSplitLayout,
        left: Int, top: Int, right: Int, bottom: Int,
        sideOnRight: Boolean,
        gapPx: Int,
        minPanePx: Int,
        sideFraction: Float? = null,
    ): SplitPanes? {
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return null
        if (layout == BrowserSplitLayout.SINGLE) return null
        // A portrait panel (a tall centre screen) split side by side gives two slivers too narrow
        // for any page, which is why the split never showed there. Stack the panes instead.
        if (height > width * PORTRAIT_RATIO) {
            return stacked(layout, left, top, right, bottom, sideOnRight, gapPx, minPanePx, sideFraction)
        }
        val gap = gapPx.coerceAtLeast(0)
        val usable = width - gap
        val dragged = sideFraction?.takeIf { it.isFinite() }?.let { clampSideFraction(it, usable, minPanePx) }
        val (sideWidth, mainWidth, mainHeight) = when {
            dragged != null -> fixed(usable, height, dragged)
            layout == BrowserSplitLayout.HALF -> fixed(usable, height, HALF_FRACTION)
            layout == BrowserSplitLayout.FORTY_SIXTY -> fixed(usable, height, FORTY_FRACTION)
            layout == BrowserSplitLayout.SIXTY_FIVE_THIRTY_FIVE ->
                fixed(usable, height, THIRTY_FIVE_FRACTION)
            else -> {
                val main = minOf((height * 16f / 9f).roundToInt(), (usable * LANDSCAPE_MAX_FRACTION).roundToInt())
                Triple(usable - main, main, (main * 9f / 16f).roundToInt().coerceAtMost(height))
            }
        }
        if (sideWidth < minPanePx || mainWidth < minPanePx) return null

        // Left-to-right in the unmirrored layout: side, gap, main.
        val sideLeft = if (sideOnRight) right - sideWidth else left
        val mainLeft = if (sideOnRight) left else right - mainWidth
        // A main pane shorter than the card (the 16:9 preset) is centred vertically.
        val mainTop = top + (height - mainHeight) / 2
        return SplitPanes(
            main = PaneRect(mainLeft, mainTop, mainLeft + mainWidth, mainTop + mainHeight),
            side = PaneRect(sideLeft, top, sideLeft + sideWidth, bottom),
        )
    }

    /**
     * [panes] for a portrait panel: the same presets and the same dragged ratio, taken from the
     * height. The side page is on top, or at the bottom when [sideOnRight]. The 16:9 preset keeps
     * its main pane 16:9 — full width, as tall as that makes it — and gives the side page the rest.
     */
    private fun stacked(
        layout: BrowserSplitLayout,
        left: Int, top: Int, right: Int, bottom: Int,
        sideOnBottom: Boolean,
        gapPx: Int,
        minPanePx: Int,
        sideFraction: Float?,
    ): SplitPanes? {
        val width = right - left
        val height = bottom - top
        val gap = gapPx.coerceAtLeast(0)
        val usable = height - gap
        val dragged = sideFraction?.takeIf { it.isFinite() }?.let { clampSideFraction(it, usable, minPanePx) }
        val sideHeight = when {
            dragged != null -> (usable * dragged).roundToInt()
            layout == BrowserSplitLayout.HALF -> (usable * HALF_FRACTION).roundToInt()
            layout == BrowserSplitLayout.FORTY_SIXTY -> (usable * FORTY_FRACTION).roundToInt()
            layout == BrowserSplitLayout.SIXTY_FIVE_THIRTY_FIVE -> (usable * THIRTY_FIVE_FRACTION).roundToInt()
            else -> usable - minOf((width * 9f / 16f).roundToInt(), (usable * LANDSCAPE_MAX_FRACTION).roundToInt())
        }
        val mainHeight = usable - sideHeight
        if (sideHeight < minPanePx || mainHeight < minPanePx) return null
        val sideTop = if (sideOnBottom) bottom - sideHeight else top
        val mainTop = if (sideOnBottom) top else bottom - mainHeight
        return SplitPanes(
            main = PaneRect(left, mainTop, right, mainTop + mainHeight),
            side = PaneRect(left, sideTop, right, sideTop + sideHeight),
            stacked = true,
        )
    }

    private fun fixed(usable: Int, height: Int, sideFraction: Float): Triple<Int, Int, Int> {
        val side = (usable * sideFraction).roundToInt()
        return Triple(side, usable - side, height)
    }

    /**
     * Holds a dragged ratio inside what the panel can actually show: both panes keep [minPanePx],
     * and neither takes more than [MAX_SIDE_FRACTION] of the width. On a panel too narrow for two
     * usable panes at once the midpoint is returned and [panes] drops back to a single page.
     */
    fun clampSideFraction(fraction: Float, usablePx: Int, minPanePx: Int): Float {
        if (usablePx <= 0) return fraction.coerceIn(MIN_SIDE_FRACTION, MAX_SIDE_FRACTION)
        val floor = maxOf(MIN_SIDE_FRACTION, minPanePx.toFloat() / usablePx)
        val ceiling = minOf(MAX_SIDE_FRACTION, 1f - minPanePx.toFloat() / usablePx)
        if (floor > ceiling) return HALF_FRACTION
        return fraction.coerceIn(floor, ceiling)
    }

    /**
     * The ratio a divider dragged by [deltaPx] lands on, starting from [panes]. The delta is in
     * surface pixels and points right; [sideOnRight] flips it, because there the side pane grows
     * as the divider moves left.
     */
    fun dragSideFraction(
        panes: SplitPanes,
        deltaPx: Int,
        sideOnRight: Boolean,
        minPanePx: Int,
    ): Float {
        // Stacked, the delta is vertical (pointing down) and the side page's height is the share.
        val usable = if (panes.stacked) panes.main.height + panes.side.height else panes.main.width + panes.side.width
        if (usable <= 0) return HALF_FRACTION
        val sideSize = if (panes.stacked) panes.side.height else panes.side.width
        val towardsSide = if (sideOnRight) -deltaPx else deltaPx
        return clampSideFraction(
            (sideSize + towardsSide).toFloat() / usable,
            usable,
            minPanePx
        )
    }
}

/**
 * Persisted split preferences for one browser surface, in the browser's shared preference file.
 *
 * Each surface keeps its own: Bridge Web (the projection route) and the Car App browser are
 * different screens the driver opens separately, and a split chosen in one used to come up in the
 * other as well — leaving AutoBridge's web screen stuck in two panes after Bridge Web was split.
 * [BrowserSplitStore] is the Car App browser's (its keys predate the split, so existing choices
 * are kept); [BrowserSplitStore.projection] is Bridge Web's.
 */
open class BrowserSplitPrefs internal constructor(private val keyPrefix: String) {
    private val keyLayout = keyPrefix + "split_layout"
    private val keySideOnRight = keyPrefix + "split_side_on_right"
    private val keySideUrl = keyPrefix + "split_side_url"
    private val keySideFraction = keyPrefix + "split_side_fraction"
    private val keyLastLayout = keyPrefix + "split_last_layout"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun layout(context: Context): BrowserSplitLayout =
        prefs(context).getString(keyLayout, null)
            ?.let { runCatching { BrowserSplitLayout.valueOf(it) }.getOrNull() }
            ?: BrowserSplitLayout.SINGLE

    /**
     * Choosing a preset drops a dragged ratio: picking one again is how you get its shape back.
     * A split preset is also remembered as the one the split switch turns back on ([toggle]).
     */
    fun setLayout(context: Context, layout: BrowserSplitLayout) {
        prefs(context).edit {
            putString(keyLayout, layout.name)
            if (layout != BrowserSplitLayout.SINGLE) putString(keyLastLayout, layout.name)
            remove(keySideFraction)
        }
    }

    /** The split preset used last, which the split switch turns back on; 50/50 before any. */
    fun lastLayout(context: Context): BrowserSplitLayout =
        prefs(context).getString(keyLastLayout, null)
            ?.let { runCatching { BrowserSplitLayout.valueOf(it) }.getOrNull() }
            ?.takeIf { it != BrowserSplitLayout.SINGLE }
            ?: BrowserSplitLayout.HALF

    /**
     * The split switch: one tap closes a split, the next brings back the layout it had. Returns
     * the layout now stored. A dragged ratio survives the round trip only when nothing else moved
     * it; closing goes through [setLayout], which drops it, so reopening gives the preset's shape.
     */
    fun toggle(context: Context): BrowserSplitLayout {
        val next = toggled(layout(context), lastLayout(context))
        setLayout(context, next)
        return next
    }

    /** The side pane's share of the width after a drag, or null while the preset's own applies. */
    fun sideFraction(context: Context): Float? =
        prefs(context).getFloat(keySideFraction, 0f).takeIf { it > 0f }

    fun setSideFraction(context: Context, fraction: Float) {
        if (!fraction.isFinite() || fraction <= 0f) return
        prefs(context).edit { putFloat(keySideFraction, fraction) }
    }

    /** Off by default: the side pane (map) sits on the left, the main page on the right. */
    fun sideOnRight(context: Context): Boolean = prefs(context).getBoolean(keySideOnRight, false)

    fun setSideOnRight(context: Context, onRight: Boolean) {
        prefs(context).edit { putBoolean(keySideOnRight, onRight) }
    }

    fun sideUrl(context: Context): String =
        prefs(context).getString(keySideUrl, null)?.let(dev.autobridge.entertainment.ContentAddress::https)
            ?: DEFAULT_SIDE_URL

    fun setSideUrl(context: Context, url: String) {
        val valid = dev.autobridge.entertainment.ContentAddress.https(url) ?: return
        prefs(context).edit { putString(keySideUrl, valid) }
    }

    companion object {
        private const val PREFS_NAME = "autobridge_browser"

        /** [toggle] without the storage: off when split, else back to [last]. */
        fun toggled(current: BrowserSplitLayout, last: BrowserSplitLayout): BrowserSplitLayout =
            if (current != BrowserSplitLayout.SINGLE) BrowserSplitLayout.SINGLE
            else last.takeIf { it != BrowserSplitLayout.SINGLE } ?: BrowserSplitLayout.HALF

        /** What the side pane opens the first time: the map case this feature exists for. */
        const val DEFAULT_SIDE_URL = "https://www.google.com/maps"
    }
}

/** The Car App browser's split preferences; see [BrowserSplitPrefs]. */
object BrowserSplitStore : BrowserSplitPrefs("") {
    /** Bridge Web's own split preferences, kept apart from the Car App browser's. */
    val projection = BrowserSplitPrefs("projection_")
}
