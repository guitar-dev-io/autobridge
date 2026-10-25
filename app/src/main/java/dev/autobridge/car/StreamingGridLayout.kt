package dev.autobridge.car

import kotlin.math.ceil
import kotlin.math.max

/** What a tap on the streaming grid can land on. */
internal enum class StreamingRegion { CARD, BANNER, SCROLL_UP, SCROLL_DOWN }

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
    /** One heading per section, at scroll 0; empty when the grid is not sectioned. */
    val headings: List<MenuBox>,
    /** The status strip above the first section, at scroll 0, or null when there is none. */
    val banner: MenuBox?,
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
        banner?.offset(-scroll)?.takeIf { it.contains(x, y) }?.let { return StreamingHit(StreamingRegion.BANNER) }
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
        const val BANNER = 48f
        const val HEADING = 36f
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
         *
         * [sections] splits the tiles, in order, into titled runs (their sizes; they add up to
         * [count]) and gives each run a heading line; null is one run with no heading, the plain
         * grid. [banner] puts a status strip above the first run. Both scroll with the cards.
         */
        fun compute(
            safe: MenuBox,
            density: Float,
            count: Int,
            hasNotes: Boolean,
            sections: List<Int>? = null,
            banner: Boolean = false
        ): StreamingGridLayout {
            val d = density
            val margin = MARGIN * d
            val gap = GAP * d
            val columns = columnsFor(safe.width / d)
            val viewTop = safe.top + HEADER * d
            val viewBottom = safe.bottom - margin / 2f
            val viewHeight = max(1f, viewBottom - viewTop)
            val labelHeight = (LABEL + if (hasNotes) NOTE else 0f) * d
            val runs = sections ?: listOf(count)
            val titled = sections != null
            val headingHeight = if (titled) HEADING * d else 0f
            val bannerHeight = if (banner) BANNER * d else 0f

            fun cardWidth(scrolling: Boolean): Float {
                val lane = if (scrolling) (SCROLL_BUTTON + SCROLL_LANE_GAP) * d else 0f
                return (safe.width - 2f * margin - lane - gap * (columns - 1)) / columns
            }

            /** Everything's top edge, at scroll 0, relative to [viewTop]; the card width sets the heights. */
            class Arranged(
                val tops: List<Float>,
                val headingTops: List<Float>,
                val bannerTop: Float,
                val pitch: Float,
                val height: Float
            )

            fun arrange(cardW: Float): Arranged {
                val cardH = cardW * CARD_ASPECT
                val pitch = cardH + labelHeight + gap
                val tops = ArrayList<Float>(count)
                val headingTops = ArrayList<Float>()
                var y = 0f
                val bannerTop = y
                if (banner) y += bannerHeight + gap
                var first = true
                for (n in runs) {
                    if (n <= 0) continue
                    if (!first) y += gap
                    first = false
                    if (titled) {
                        headingTops += y
                        y += headingHeight
                    }
                    val rows = ceil(n / columns.toFloat()).toInt()
                    for (i in 0 until n) tops += y + (i / columns) * pitch
                    y += rows * pitch - gap
                }
                return Arranged(tops, headingTops, bannerTop, pitch, max(0f, y))
            }

            var scrolling = false
            var cardW = cardWidth(false)
            if (arrange(cardW).height > viewHeight) {
                scrolling = true
                cardW = cardWidth(true)
            }
            val cardH = cardW * CARD_ASPECT
            val arranged = arrange(cardW)
            val lane = if (scrolling) (SCROLL_BUTTON + SCROLL_LANE_GAP) * d else 0f
            val gridLeft = safe.left + margin + lane

            val cards = ArrayList<MenuBox>(count)
            val labels = ArrayList<MenuBox>(count)
            for (i in 0 until count) {
                val left = gridLeft + (i % columns) * (cardW + gap)
                val top = viewTop + arranged.tops[i]
                cards += MenuBox(left, top, left + cardW, top + cardH)
                labels += MenuBox(left, top + cardH, left + cardW, top + cardH + labelHeight)
            }
            val gridRight = gridLeft + columns * cardW + (columns - 1) * gap
            val headings = arranged.headingTops.map { top ->
                MenuBox(gridLeft, viewTop + top, gridRight, viewTop + top + headingHeight)
            }
            val bannerBox = if (banner) {
                MenuBox(gridLeft, viewTop + arranged.bannerTop, gridRight, viewTop + arranged.bannerTop + bannerHeight)
            } else null
            val button = SCROLL_BUTTON * d
            val buttonLeft = safe.left + margin
            return StreamingGridLayout(
                columns = columns,
                cards = cards,
                labels = labels,
                headings = headings,
                banner = bannerBox,
                title = MenuBox(safe.left + margin, safe.top, safe.right - margin, safe.top + HEADER * d),
                viewport = MenuBox(safe.left, viewTop, safe.right, viewBottom),
                maxScroll = if (scrolling) max(0f, arranged.height - viewHeight) else 0f,
                scrollUp = if (scrolling) MenuBox(buttonLeft, viewTop, buttonLeft + button, viewTop + button) else null,
                scrollDown = if (scrolling) MenuBox(buttonLeft, viewBottom - button, buttonLeft + button, viewBottom) else null,
                scrollStep = arranged.pitch,
                density = d
            )
        }
    }
}
