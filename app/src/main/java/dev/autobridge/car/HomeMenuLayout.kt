package dev.autobridge.car

import dev.autobridge.car.HomeMenuTheme.Dp
import kotlin.math.max
import kotlin.math.min

/** Axis-aligned box in surface pixels. Kept free of android.graphics so the maths stays pure. */
internal data class MenuBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
    fun offset(dy: Float) = copy(top = top + dy, bottom = bottom + dy)
}

/**
 * Responsive geometry for the 3 × 2 home menu, computed from the area the host leaves free.
 *
 * Nothing here is tuned to one screenshot: card width comes from the container width, card height
 * from what is left under the header, and both are clamped so a small head unit gets a compact
 * header first and only falls back to scrolling (with [scrollUp] / [scrollDown] buttons) when even
 * minimum-height cards cannot fit. On roomy displays cards stop growing and the grid is centred.
 */
internal class HomeMenuLayout private constructor(
    val density: Float,
    val logo: MenuBox,
    /** Title start; it is centred on [logo] vertically by the renderer, which owns the font metrics. */
    val titleX: Float,
    val titleSize: Float,
    /** Clip region of the grid; equal to the grid's bounds unless scrolling is needed. */
    val viewport: MenuBox,
    /** Card bounds at scroll offset 0, row-major. */
    val cards: List<MenuBox>,
    val cornerRadius: Float,
    val iconSize: Float,
    val iconLabelGap: Float,
    val labelSize: Float,
    val labelMaxWidth: Float,
    val maxScroll: Float,
    val scrollUp: MenuBox?,
    val scrollDown: MenuBox?
) {
    val scrollable: Boolean get() = maxScroll > 0f

    fun dp(value: Float) = value * density

    /** Index of the card under ([x], [y]) at [scroll], or -1. Only visible card area hits. */
    fun cardAt(x: Float, y: Float, scroll: Float): Int {
        if (!viewport.contains(x, y)) return -1
        return cards.indexOfFirst { it.offset(-scroll).contains(x, y) }
    }

    /** One row plus its gap: what a single scroll-button tap moves. */
    val scrollStep: Float
        get() = if (cards.size > COLUMNS) cards[COLUMNS].top - cards[0].top else 0f

    companion object {
        const val COLUMNS = 3

        /**
         * @param safe area not covered by host chrome (visible/stable area), in surface pixels.
         * @param measure width of a label at a text size in pixels, from the real paint.
         */
        fun compute(
            safe: MenuBox,
            density: Float,
            labels: List<String>,
            measure: (String, Float) -> Float
        ): HomeMenuLayout {
            val d = density
            val rows = (labels.size + COLUMNS - 1) / COLUMNS
            val margin = (safe.width * 0.035f).coerceIn(Dp.MARGIN_MIN * d, Dp.MARGIN_MAX * d)
            val bottom = safe.bottom - Dp.BOTTOM_PADDING * d

            data class Pass(val top: Float, val logo: Float, val title: Float, val headerGap: Float, val gap: Float) {
                val gridTop get() = top + logo + headerGap
                fun cardHeight(bottom: Float, rows: Int) = (bottom - gridTop - gap * (rows - 1)) / rows
            }
            val regular = Pass(safe.top + Dp.TOP_PADDING * d, Dp.LOGO * d, Dp.TITLE * d, Dp.HEADER_TO_GRID * d, Dp.GAP * d)
            val compact = Pass(
                safe.top + Dp.TOP_PADDING_COMPACT * d, Dp.LOGO_COMPACT * d, Dp.TITLE_COMPACT * d,
                Dp.HEADER_TO_GRID_COMPACT * d, Dp.GAP_COMPACT * d
            )
            val minCard = Dp.CARD_MIN_HEIGHT * d
            val pass = if (regular.cardHeight(bottom, rows) >= minCard) regular else compact
            val fitted = pass.cardHeight(bottom, rows)
            val scrolling = fitted < minCard
            val gap = pass.gap

            // Scroll buttons get their own column at the start edge, outside the cards.
            val buttonSize = Dp.SCROLL_BUTTON * d
            val gridAreaLeft = safe.left + margin + if (scrolling) buttonSize + Dp.SCROLL_COLUMN_GAP * d else 0f
            val gridAreaRight = safe.right - margin

            var cardHeight = if (scrolling) minCard else min(fitted, Dp.CARD_MAX_HEIGHT * d)
            var cardWidth = (gridAreaRight - gridAreaLeft - gap * (COLUMNS - 1)) / COLUMNS
            cardWidth = min(cardWidth, cardHeight * HomeMenuTheme.CARD_MAX_ASPECT)
            // Very narrow units: never let a card be taller than it is wide-ish.
            cardHeight = min(cardHeight, max(cardWidth * 1.1f, minCard))
            val gridWidth = cardWidth * COLUMNS + gap * (COLUMNS - 1)
            val gridLeft = gridAreaLeft + (gridAreaRight - gridAreaLeft - gridWidth) / 2f

            val cards = labels.indices.map { index ->
                val col = index % COLUMNS
                val row = index / COLUMNS
                val left = gridLeft + col * (cardWidth + gap)
                val top = pass.gridTop + row * (cardHeight + gap)
                MenuBox(left, top, left + cardWidth, top + cardHeight)
            }
            val contentBottom = cards.maxOfOrNull { it.bottom } ?: pass.gridTop
            val viewport = MenuBox(gridLeft, pass.gridTop, gridLeft + gridWidth, if (scrolling) bottom else contentBottom)
            val maxScroll = max(0f, contentBottom - viewport.bottom)

            // Card contents: icon tile, gap, one label line, centred as a block.
            val padding = Dp.CARD_PADDING * d
            val labelMaxWidth = cardWidth - padding * 2
            val labelFloor = Dp.LABEL_MIN * d
            var labelSize = min(Dp.LABEL_MAX * d, cardHeight * 0.18f).coerceAtLeast(labelFloor)
            // One size for every card, so "TV" and "YouTube Music" read as the same component.
            while (labelSize > labelFloor && labels.any { measure(it, labelSize) > labelMaxWidth }) {
                labelSize -= 0.5f * d
            }
            labelSize = max(labelSize, labelFloor)
            val iconLabelGap = (cardHeight * 0.08f).coerceIn(Dp.ICON_LABEL_GAP_MIN * d, Dp.ICON_LABEL_GAP_MAX * d)
            val iconRoom = cardHeight - padding * 2 - iconLabelGap - labelSize * 1.2f
            val iconSize = min(min(cardHeight * 0.52f, cardWidth * 0.45f), iconRoom)
                .coerceIn(Dp.ICON_MIN * d, Dp.ICON_MAX * d)

            // Header aligns with the grid's start edge, logo and title share a vertical centre.
            val headerLeft = if (scrolling) safe.left + margin else gridLeft
            val logo = MenuBox(headerLeft, pass.top, headerLeft + pass.logo, pass.top + pass.logo)

            val scrollUp = if (scrolling) MenuBox(
                safe.left + margin, viewport.top, safe.left + margin + buttonSize, viewport.top + buttonSize
            ) else null
            val scrollDown = if (scrolling) MenuBox(
                safe.left + margin, viewport.bottom - buttonSize, safe.left + margin + buttonSize, viewport.bottom
            ) else null

            return HomeMenuLayout(
                density = d,
                logo = logo,
                titleX = logo.right + Dp.LOGO_TO_TITLE * d,
                titleSize = pass.title,
                viewport = viewport,
                cards = cards,
                cornerRadius = min(Dp.CARD_RADIUS * d, cardHeight * 0.18f),
                iconSize = iconSize,
                iconLabelGap = iconLabelGap,
                labelSize = labelSize,
                labelMaxWidth = labelMaxWidth,
                maxScroll = maxScroll,
                scrollUp = scrollUp,
                scrollDown = scrollDown
            )
        }
    }
}
