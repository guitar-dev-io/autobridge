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
    MOVE,

    /**
     * Turns the phone's panel off (or back on) without letting the phone sleep — the screen-off
     * the panes survive, unlike the power key. See CarScreenPower.turnPanelOff.
     */
    PHONE_SCREEN
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
 * Pure placement for [DuoScreenControlsLayout]. Sizes are the design's own pixels (drawn on a
 * 1074 px wide surface) multiplied by [DuoScreenChrome.scale], so the bar keeps the proportions of
 * docs/design/13 on any head unit and always fits the seam gutter [DuoScreenChrome] leaves.
 *
 * Rule (docs/UI_REDESIGN_TASKS.md, Phase 7):
 * - two panes sharing a seam → a bar in the seam gutter: LAYOUT and SWAP at the start of the seam,
 *   PHONE_SCREEN, RELOAD and ARRANGE (or DONE) at its end, the drag handle in the middle;
 * - no shared seam (picture in picture, or panes dragged apart) → one floating button.
 */
object DuoScreenControlsGeometry {
    const val BUTTON_DP = 44f
    const val GAP_DP = 8f

    /** From the end of the seam to the first button: the 12 px margin plus the row's 4 px padding. */
    const val END_INSET_DP = 16f
    const val HANDLE_LENGTH_DP = 96f
    const val HANDLE_THICKNESS_DP = 6f

    /**
     * Smallest area a press may land in, whatever the drawn size. Larger than the 44 px button and
     * the 52 px gutter on purpose: a fingertip on a car panel misses by more than the button is
     * wide, so the hit area reaches a few pixels into the panes beside each button.
     */
    const val MIN_HIT_DP = 64f
    const val FAB_DP = 60f
    const val MENU_BUTTON_DP = 52f
    const val FAB_MARGIN_DP = 28f
    const val MENU_GAP_DP = 12f

    fun layout(
        panes: List<DuoScreenPane>,
        bounds: Bounds,
        editing: Boolean,
        menuOpen: Boolean,
        corner: DuoScreenFabCorner,
        scale: Float = DuoScreenChrome.scale(bounds)
    ): DuoScreenControlsLayout {
        val d = if (scale.isFinite() && scale > 0f) scale else 1f
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
        val inset = px(END_INSET_DP, d)
        val handleLength = px(HANDLE_LENGTH_DP, d)
        val handleThickness = px(HANDLE_THICKNESS_DP, d)
        val minHit = px(MIN_HIT_DP, d)
        val gutter = px(DuoScreenChrome.SEAM_GUTTER, d)

        val leading = listOf(DuoScreenControl.LAYOUT, DuoScreenControl.SWAP)
        val trailing = if (editing) {
            listOf(DuoScreenControl.DONE)
        } else {
            listOf(DuoScreenControl.PHONE_SCREEN, DuoScreenControl.RELOAD, DuoScreenControl.ARRANGE)
        }

        val horizontal = seam.axis == Axis.HORIZONTAL
        val boundsAcross = if (horizontal) bounds.height else bounds.width
        // Centred across the seam, i.e. in the middle of the gutter between the two panes.
        val buttonAcross = (seam.position - button / 2).coerceIn(0, (boundsAcross - button).coerceAtLeast(0))
        val gutterAcross = (seam.position - gutter / 2).coerceIn(0, (boundsAcross - gutter).coerceAtLeast(0))

        fun rectAt(along: Int, acrossOffset: Int, alongSize: Int, acrossSize: Int): Rect =
            if (horizontal) Rect(along, acrossOffset, alongSize, acrossSize)
            else Rect(acrossOffset, along, acrossSize, alongSize)

        val placed = mutableListOf<DuoScreenControlButton>()
        fun place(control: DuoScreenControl, along: Int) {
            val rect = rectAt(along, buttonAcross, button, button)
            placed += DuoScreenControlButton(control, rect, hitArea(rect, minHit, bounds))
        }

        var cursor = seam.from + inset
        leading.forEach { control ->
            place(control, cursor)
            cursor += button + gap
        }

        val centre = (seam.from + seam.to) / 2
        val handleStart = centre - handleLength / 2
        val handleRect = rectAt(
            handleStart,
            seam.position - handleThickness / 2,
            handleLength,
            handleThickness
        )
        // The handle is thin to look at but must be as easy to press as a button.
        val handleHit = rectAt(handleStart, gutterAcross, handleLength, gutter)
        placed += DuoScreenControlButton(
            DuoScreenControl.HANDLE,
            handleRect,
            hitArea(handleHit, minHit, bounds)
        )

        var end = seam.to - inset
        trailing.reversed().forEach { control ->
            end -= button
            place(control, end)
            end -= gap
        }
        // Keep the order start → end (LAYOUT, SWAP, HANDLE, PHONE_SCREEN, RELOAD, ARRANGE).
        val ordered = placed.take(leading.size + 1) + placed.drop(leading.size + 1).reversed()
        return DuoScreenControlsLayout(DuoScreenControlsLayout.Kind.SEAM_BAR, panel = null, buttons = ordered)
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
                DuoScreenControl.PHONE_SCREEN,
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
