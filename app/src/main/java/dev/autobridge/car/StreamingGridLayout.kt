package dev.autobridge.car

import kotlin.math.ceil
import kotlin.math.max

/** What a tap on the streaming grid can land on. */
internal enum class StreamingRegion { CARD, SCROLL_UP, SCROLL_DOWN }

/** A resolved tap: which region, and which tile when it is a card. */
internal data class StreamingHit(val region: StreamingRegion, val index: Int = -1)

/**
 * Where the streaming launcher's brand cards sit on the car surface: [columns] to a row, each a
 * wide card (8:5) with its name under it, in rows that scroll when they do not all fit.
 *
 * The host's own grid template picks its column count itself (four on a wide screen); drawing the
 * grid here is what makes it three, with cards big enough to hit. Pure arithmetic in surface
 * pixels, so the rules are tested without a head unit. Dimensions are dp on the head unit's own
 * density ([compute]'s `density`, pixels per dp), the way the car home's layout takes it.
 */
internal class StreamingGridLayout private constructor(
    val columns: Int,
    /** The card images at scroll 0, row-major. */
    val cards: List<MenuBox>,
    /** The strip under each card for its name (and a note), at scroll 0. */
    val labels: List<MenuBox>,
    val title: MenuBox,
    /** Where the cards scroll; they are clipped to it. */
    val viewport: MenuBox,
    val maxScroll: Float,
    val scrollUp: MenuBox?,
    val scrollDown: MenuBox?,
    /** One row of cards, the distance a scroll button moves. */
    val scrollStep: Float,
    val density: Float
) {
    val scrollable: Boolean get() = maxScroll > 0f

    /** The tile under ([x], [y]) with the grid scrolled by [scroll], or null. */
    fun hit(x: Float, y: Float, scroll: Float): StreamingHit? {
        scrollUp?.takeIf { it.contains(x, y) }?.let { return StreamingHit(StreamingRegion.SCROLL_UP) }
        scrollDown?.takeIf { it.contains(x, y) }?.let { return StreamingHit(StreamingRegion.SCROLL_DOWN) }
        if (!viewport.contains(x, y)) return null
        for (i in cards.indices) {
            val card = cards[i].offset(-scroll)
            val label = labels[i].offset(-scroll)
            if (card.contains(x, y) || label.contains(x, y)) return StreamingHit(StreamingRegion.CARD, i)
        }
        return null
    }

    companion object {
        const val MARGIN = 24f
        const val GAP = 16f
        const val HEADER = 56f
        const val LABEL = 24f
        const val NOTE = 18f
        const val SCROLL_BUTTON = 56f
        const val SCROLL_LANE_GAP = 14f

        /** Card height over width: 8:5, the shape [dev.autobridge.library.StreamingIcons.cardBitmap] draws. */
        const val CARD_ASPECT = 0.625f

        /** At least this wide (dp) for three columns; narrower, such as a portrait unit, gets two. */
        const val THREE_COLUMNS_MIN_DP = 480f

        fun columnsFor(widthDp: Float): Int = if (widthDp >= THREE_COLUMNS_MIN_DP) 3 else 2

        /**
         * Lays out [count] tiles inside [safe] (the host's stable area). [hasNotes] adds a line
         * under each name for the "may not play" note.
         */
        fun compute(safe: MenuBox, density: Float, count: Int, hasNotes: Boolean): StreamingGridLayout {
            val d = density
            val margin = MARGIN * d
            val gap = GAP * d
            val columns = columnsFor(safe.width / d)
            val viewTop = safe.top + HEADER * d
            val viewBottom = safe.bottom - margin / 2f
            val viewHeight = max(1f, viewBottom - viewTop)
            val labelHeight = (LABEL + if (hasNotes) NOTE else 0f) * d
            val rows = ceil(count / columns.toFloat()).toInt().coerceAtLeast(1)

            fun cardWidth(scrolling: Boolean): Float {
                val lane = if (scrolling) (SCROLL_BUTTON + SCROLL_LANE_GAP) * d else 0f
                return (safe.width - 2f * margin - lane - gap * (columns - 1)) / columns
            }

            fun contentHeight(cardW: Float) = rows * (cardW * CARD_ASPECT + labelHeight + gap) - gap

            var scrolling = false
            var cardW = cardWidth(false)
            if (contentHeight(cardW) > viewHeight) {
                scrolling = true
                cardW = cardWidth(true)
            }
            val cardH = cardW * CARD_ASPECT
            val pitch = cardH + labelHeight + gap
            val contentH = rows * pitch - gap
            val lane = if (scrolling) (SCROLL_BUTTON + SCROLL_LANE_GAP) * d else 0f
            val gridLeft = safe.left + margin + lane

            val cards = ArrayList<MenuBox>(count)
            val labels = ArrayList<MenuBox>(count)
            for (i in 0 until count) {
                val left = gridLeft + (i % columns) * (cardW + gap)
                val top = viewTop + (i / columns) * pitch
                cards += MenuBox(left, top, left + cardW, top + cardH)
                labels += MenuBox(left, top + cardH, left + cardW, top + cardH + labelHeight)
            }
            val button = SCROLL_BUTTON * d
            val buttonLeft = safe.left + margin
            return StreamingGridLayout(
                columns = columns,
                cards = cards,
                labels = labels,
                title = MenuBox(safe.left + margin, safe.top, safe.right - margin, safe.top + HEADER * d),
                viewport = MenuBox(safe.left, viewTop, safe.right, viewBottom),
                maxScroll = if (scrolling) max(0f, contentH - viewHeight) else 0f,
                scrollUp = if (scrolling) MenuBox(buttonLeft, viewTop, buttonLeft + button, viewTop + button) else null,
                scrollDown = if (scrolling) MenuBox(buttonLeft, viewBottom - button, buttonLeft + button, viewBottom) else null,
                scrollStep = pitch,
                density = d
            )
        }
    }
}
