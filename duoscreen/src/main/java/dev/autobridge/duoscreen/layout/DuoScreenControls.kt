package dev.autobridge.duoscreen.layout

import dev.autobridge.duoscreen.layout.DuoScreenLayout.Axis
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Bounds
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Divider
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Rect

/**
 * The buttons Duo Screen draws on the car surface itself, instead of asking the host for them.
 *
 * Host action-strip buttons are drawn by Android Auto at a size the app cannot change, and on a
 * portrait head unit four of them sat on top of the right-hand pane. Only Exit stays in the host
 * strip (NavigationTemplate will not build without one action); everything else is laid out here
 * and painted by the compositor, and taps on it are caught before they reach a pane.
 */
enum class DuoScreenControl {
    LAYOUT,
    SWAP,
    RELOAD,
    ARRANGE,
    DONE,

    /** The pill on the seam: tapping it enters arrange mode with the seam grabbed. */
    HANDLE,

    /** The floating button (picture in picture): opens and closes its menu. */
    MENU,

    /** In the floating menu: sends the floating button to the next corner. */
    MOVE
}

/**
 * Where the floating button sits. Dragging it directly is impossible on Android Auto — the host
 * reports a scroll as a bare distance with no start point, so nothing can tell a drag that began
 * on the button from one that began on a pane — so it is moved one corner per tap instead.
 */
enum class DuoScreenFabCorner {
    BOTTOM_RIGHT,
    BOTTOM_LEFT,
    TOP_LEFT,
    TOP_RIGHT;

    fun next(): DuoScreenFabCorner = entries[(ordinal + 1) % entries.size]
}

/** One control: where it is drawn, and the larger area a fingertip may land in to press it. */
data class DuoScreenControlButton(
    val control: DuoScreenControl,
    val rect: Rect,
    val hit: Rect
)

data class DuoScreenControlsLayout(
    val kind: Kind,
    /** The rounded backing behind a seam bar, or null for the floating button. */
    val panel: Rect?,
    val buttons: List<DuoScreenControlButton>,
    /** True while the floating menu is open, so the painter can dim what is behind it. */
    val menuOpen: Boolean = false
) {
    enum class Kind { SEAM_BAR, FLOATING }

    /** The control under (x, y), topmost first, or null when the tap belongs to a pane. */
    fun controlAt(x: Int, y: Int): DuoScreenControl? =
        buttons.lastOrNull { it.hit.contains(x, y) }?.control

    /** Everything that is painted, so the overlay bitmap only needs to cover this much. */
    val paintedArea: Rect?
        get() {
            val rects = buttons.map { it.rect } + listOfNotNull(panel)
            if (rects.isEmpty()) return null
            val left = rects.minOf { it.left }
            val top = rects.minOf { it.top }
            val right = rects.maxOf { it.right }
            val bottom = rects.maxOf { it.bottom }
            return Rect(left, top, right - left, bottom - top)
        }
}

/**
 * Pure placement for [DuoScreenControlsLayout]. Sizes are in dp and scaled by the surface's
 * density, so a 160 dpi DHU and a 320 dpi head unit get the same physical buttons.
 *
 * Rule (docs/UI_REDESIGN_TASKS.md, Phase 7):
 * - two panes sharing a seam → a compact bar centred on that seam;
 * - no shared seam (picture in picture, or panes dragged apart) → one floating button.
 */
object DuoScreenControlsGeometry {
    const val BUTTON_DP = 56f
    const val GAP_DP = 10f
    const val PANEL_PADDING_DP = 8f
    const val HANDLE_LENGTH_DP = 120f
    const val HANDLE_THICKNESS_DP = 8f

    /** Smallest area a press may land in, whatever the drawn size. */
    const val MIN_HIT_DP = 76f
    const val FAB_DP = 76f
    const val MENU_BUTTON_DP = 68f
    const val FAB_MARGIN_DP = 36f
    const val MENU_GAP_DP = 16f

    fun layout(
        panes: List<DuoScreenPane>,
        bounds: Bounds,
        editing: Boolean,
        menuOpen: Boolean,
        corner: DuoScreenFabCorner,
        density: Float
    ): DuoScreenControlsLayout {
        val d = if (density.isFinite() && density > 0f) density else 1f
        val seam = sharedSeam(panes)
        return if (seam != null) {
            seamBar(seam, bounds, editing, d)
        } else {
            floating(bounds, editing, menuOpen, corner, d)
        }
    }

    /** The seam between the two lowest-id panes, if they share one. */
    fun sharedSeam(panes: List<DuoScreenPane>): Divider? {
        val byId = panes.sortedBy { it.id }
        if (byId.size < 2) return null
        val a = byId[0]
        val b = byId[1]
        return DuoScreenLayout.dividerBetween(a.id, a.rect, b.id, b.rect, Axis.HORIZONTAL)
            ?: DuoScreenLayout.dividerBetween(a.id, a.rect, b.id, b.rect, Axis.VERTICAL)
    }

    private fun seamBar(seam: Divider, bounds: Bounds, editing: Boolean, d: Float): DuoScreenControlsLayout {
        val button = px(BUTTON_DP, d)
        val gap = px(GAP_DP, d)
        val pad = px(PANEL_PADDING_DP, d)
        val handleLength = px(HANDLE_LENGTH_DP, d)
        val handleThickness = px(HANDLE_THICKNESS_DP, d)
        val minHit = px(MIN_HIT_DP, d)

        val leading = listOf(DuoScreenControl.LAYOUT, DuoScreenControl.SWAP)
        val trailing = if (editing) {
            listOf(DuoScreenControl.DONE)
        } else {
            listOf(DuoScreenControl.RELOAD, DuoScreenControl.ARRANGE)
        }

        // Along the seam: pad, leading buttons, handle, trailing buttons, pad.
        val buttonsLength = (leading.size + trailing.size) * button
        val gaps = (leading.size + trailing.size) * gap
        val length = pad * 2 + buttonsLength + gaps + handleLength
        val thickness = pad * 2 + button

        val centreAlong = (seam.from + seam.to) / 2
        val horizontal = seam.axis == Axis.HORIZONTAL
        val boundsAlong = if (horizontal) bounds.width else bounds.height
        val boundsAcross = if (horizontal) bounds.height else bounds.width
        val start = (centreAlong - length / 2).coerceIn(0, (boundsAlong - length).coerceAtLeast(0))
        val across = (seam.position - thickness / 2).coerceIn(0, (boundsAcross - thickness).coerceAtLeast(0))

        fun rectAt(along: Int, acrossOffset: Int, alongSize: Int, acrossSize: Int): Rect =
            if (horizontal) Rect(along, acrossOffset, alongSize, acrossSize)
            else Rect(acrossOffset, along, acrossSize, alongSize)

        val panel = rectAt(start, across, length, thickness)
        val placed = mutableListOf<DuoScreenControlButton>()
        var cursor = start + pad
        val buttonAcross = across + pad
        leading.forEach { control ->
            val rect = rectAt(cursor, buttonAcross, button, button)
            placed += DuoScreenControlButton(control, rect, hitArea(rect, minHit, bounds))
            cursor += button + gap
        }
        val handleRect = rectAt(
            cursor,
            across + (thickness - handleThickness) / 2,
            handleLength,
            handleThickness
        )
        // The handle is thin to look at but must be as easy to press as a button.
        val handleHit = rectAt(cursor, across, handleLength, thickness)
        placed += DuoScreenControlButton(
            DuoScreenControl.HANDLE,
            handleRect,
            hitArea(handleHit, minHit, bounds)
        )
        cursor += handleLength + gap
        trailing.forEach { control ->
            val rect = rectAt(cursor, buttonAcross, button, button)
            placed += DuoScreenControlButton(control, rect, hitArea(rect, minHit, bounds))
            cursor += button + gap
        }
        return DuoScreenControlsLayout(DuoScreenControlsLayout.Kind.SEAM_BAR, panel, placed)
    }

    private fun floating(
        bounds: Bounds,
        editing: Boolean,
        menuOpen: Boolean,
        corner: DuoScreenFabCorner,
        d: Float
    ): DuoScreenControlsLayout {
        val fab = px(FAB_DP, d)
        val margin = px(FAB_MARGIN_DP, d)
        val item = px(MENU_BUTTON_DP, d)
        val gap = px(MENU_GAP_DP, d)
        val minHit = px(MIN_HIT_DP, d)

        val right = corner == DuoScreenFabCorner.BOTTOM_RIGHT || corner == DuoScreenFabCorner.TOP_RIGHT
        val bottom = corner == DuoScreenFabCorner.BOTTOM_RIGHT || corner == DuoScreenFabCorner.BOTTOM_LEFT
        val fabLeft = if (right) bounds.width - margin - fab else margin
        val fabTop = if (bottom) bounds.height - margin - fab else margin
        val fabRect = Rect(fabLeft.coerceAtLeast(0), fabTop.coerceAtLeast(0), fab, fab)

        val placed = mutableListOf<DuoScreenControlButton>()
        if (menuOpen) {
            val items = listOf(
                DuoScreenControl.LAYOUT,
                DuoScreenControl.SWAP,
                DuoScreenControl.RELOAD,
                if (editing) DuoScreenControl.DONE else DuoScreenControl.ARRANGE,
                DuoScreenControl.MOVE
            )
            // The menu opens away from the edge the button sits on: upward from a bottom corner.
            val itemLeft = fabRect.left + (fab - item) / 2
            items.forEachIndexed { index, control ->
                val top = if (bottom) {
                    fabRect.top - (index + 1) * (item + gap)
                } else {
                    fabRect.bottom + gap + index * (item + gap)
                }
                val rect = Rect(itemLeft, top, item, item)
                placed += DuoScreenControlButton(control, rect, hitArea(rect, minHit, bounds))
            }
        }
        // Added last so it wins a tap where its hit area meets a menu item's.
        placed += DuoScreenControlButton(DuoScreenControl.MENU, fabRect, hitArea(fabRect, minHit, bounds))
        return DuoScreenControlsLayout(
            DuoScreenControlsLayout.Kind.FLOATING,
            panel = null,
            buttons = placed,
            menuOpen = menuOpen
        )
    }

    /** [rect] grown symmetrically to at least [minSize] on each side, kept inside [bounds]. */
    fun hitArea(rect: Rect, minSize: Int, bounds: Bounds): Rect {
        val width = maxOf(rect.width, minSize)
        val height = maxOf(rect.height, minSize)
        val left = (rect.left - (width - rect.width) / 2).coerceIn(0, (bounds.width - width).coerceAtLeast(0))
        val top = (rect.top - (height - rect.height) / 2).coerceIn(0, (bounds.height - height).coerceAtLeast(0))
        return Rect(left, top, width.coerceAtMost(bounds.width), height.coerceAtMost(bounds.height))
    }

    private fun px(dp: Float, density: Float): Int = (dp * density + 0.5f).toInt()
}
