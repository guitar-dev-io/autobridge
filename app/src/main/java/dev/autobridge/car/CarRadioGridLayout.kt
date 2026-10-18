package dev.autobridge.car

/** What a tap on the radio grid surface stands for. */
internal sealed interface CarRadioGridRegion {
    /** The round arrow at the start of the header: back to the AutoBridge home. */
    object Back : CarRadioGridRegion
    object Search : CarRadioGridRegion
    data class Tab(val index: Int) : CarRadioGridRegion
    data class Cell(val index: Int) : CarRadioGridRegion
    /** The one button under a message (pick a province). */
    object MessageAction : CarRadioGridRegion
}

/**
 * Where everything sits on the car radio grid, after the radio design's car screen (CarHome): a
 * header row with a round back button, the category tabs as pills and a round search button, and
 * under it the stations as a grid of cards, [COLUMNS] across so each logo is big enough to find at
 * a glance. Pure arithmetic in surface pixels so it can be tested without a car.
 *
 * The grid scrolls: [cellBox] places cell `i` in content coordinates and [gridViewport] is the part
 * of the surface it is drawn into; the screen keeps the offset, clamped by [maxScroll].
 */
internal class CarRadioGridLayout private constructor(
    val density: Float,
    val content: CarRadioBox,
    val back: CarRadioBox,
    val search: CarRadioBox,
    val tabs: List<CarRadioBox>,
    val gridViewport: CarRadioBox,
    val cellWidth: Float,
    val cellHeight: Float,
    val imageHeight: Float,
    val gap: Float,
    val cellCount: Int,
    val message: CarRadioBox,
    val messageAction: CarRadioBox,
) {
    fun dp(v: Float) = v * density

    val rows: Int get() = (cellCount + COLUMNS - 1) / COLUMNS

    /** The grid's full height; what does not fit in [gridViewport] is reached by scrolling. */
    val gridHeight: Float get() = if (rows == 0) 0f else rows * cellHeight + (rows - 1) * gap

    val maxScroll: Float get() = (gridHeight - gridViewport.height).coerceAtLeast(0f)

    /** Cell [index] on the surface with the grid scrolled by [scroll]. */
    fun cellBox(index: Int, scroll: Float): CarRadioBox {
        val row = index / COLUMNS
        val column = index % COLUMNS
        val left = gridViewport.left + column * (cellWidth + gap)
        val top = gridViewport.top + row * (cellHeight + gap) - scroll
        return CarRadioBox(left, top, left + cellWidth, top + cellHeight)
    }

    fun hit(x: Float, y: Float, scroll: Float, showsMessageAction: Boolean): CarRadioGridRegion? {
        if (inside(back, x, y)) return CarRadioGridRegion.Back
        if (inside(search, x, y)) return CarRadioGridRegion.Search
        tabs.forEachIndexed { index, box -> if (inside(box, x, y)) return CarRadioGridRegion.Tab(index) }
        if (showsMessageAction && inside(messageAction, x, y)) return CarRadioGridRegion.MessageAction
        if (!inside(gridViewport, x, y)) return null
        for (index in 0 until cellCount) {
            if (inside(cellBox(index, scroll), x, y)) return CarRadioGridRegion.Cell(index)
        }
        return null
    }

    private fun inside(box: CarRadioBox, x: Float, y: Float) =
        box.width > 0f && x >= box.left && x <= box.right && y >= box.top && y <= box.bottom

    companion object {
        const val COLUMNS = 3
        const val HEADER_DP = 56f
        const val TAB_GAP_DP = 8f
        /** Space around a tab's icon and label; [compute] shrinks it before it shrinks the text. */
        const val TAB_PADDING_DP = 18f
        const val TAB_ICON_DP = 24f
        const val TAB_ICON_GAP_DP = 8f
        const val CELL_PADDING_DP = 6f
        const val NAME_BLOCK_DP = 40f

        /**
         * [tabLabelWidths]: each tab label's drawn width in pixels, measured by the renderer with
         * the paint it draws them with. When the pills do not fit between the two round buttons
         * their padding shrinks first, then they share the width evenly.
         */
        fun compute(area: CarRadioBox, density: Float, tabLabelWidths: List<Float>, cellCount: Int): CarRadioGridLayout {
            fun dp(v: Float) = v * density
            val content = CarRadioBox(area.left + dp(26f), area.top + dp(18f), area.right - dp(26f), area.bottom - dp(16f))
            val header = dp(HEADER_DP)
            val headerBottom = content.top + header
            val back = CarRadioBox(content.left, content.top, content.left + header, headerBottom)
            val search = CarRadioBox(content.right - header, content.top, content.right, headerBottom)

            val tabsLeft = back.right + dp(10f)
            val tabsRight = search.left - dp(10f)
            val available = (tabsRight - tabsLeft).coerceAtLeast(0f)
            val gaps = dp(TAB_GAP_DP) * (tabLabelWidths.size - 1).coerceAtLeast(0)
            val fixed = dp(TAB_ICON_DP + TAB_ICON_GAP_DP)
            fun widths(padding: Float) = tabLabelWidths.map { it + fixed + padding * 2 }
            var tabWidths = widths(dp(TAB_PADDING_DP))
            if (tabWidths.sum() + gaps > available) tabWidths = widths(dp(10f))
            if (tabWidths.sum() + gaps > available && tabWidths.isNotEmpty()) {
                val even = ((available - gaps) / tabWidths.size).coerceAtLeast(0f)
                tabWidths = tabWidths.map { even }
            }
            var x = tabsLeft
            val tabs = tabWidths.map { width ->
                CarRadioBox(x, content.top, x + width, headerBottom).also { x += width + dp(TAB_GAP_DP) }
            }

            val gap = dp(14f)
            val viewport = CarRadioBox(content.left, headerBottom + dp(14f), content.right, content.bottom)
            val cellWidth = ((viewport.width - gap * (COLUMNS - 1)) / COLUMNS).coerceAtLeast(1f)
            val imageHeight = (cellWidth * 0.55f).coerceIn(dp(96f), dp(160f)).coerceAtMost(cellWidth)
            val cellHeight = imageHeight + dp(NAME_BLOCK_DP) + dp(CELL_PADDING_DP) * 2

            val message = CarRadioBox(viewport.left, viewport.top, viewport.right, viewport.top + viewport.height * 0.6f)
            val actionWidth = dp(260f).coerceAtMost(viewport.width)
            val messageAction = CarRadioBox(
                viewport.centerX - actionWidth / 2f, message.bottom,
                viewport.centerX + actionWidth / 2f, message.bottom + dp(64f)
            )
            return CarRadioGridLayout(
                density, content, back, search, tabs, viewport, cellWidth, cellHeight, imageHeight, gap,
                cellCount, message, messageAction
            )
        }
    }
}
