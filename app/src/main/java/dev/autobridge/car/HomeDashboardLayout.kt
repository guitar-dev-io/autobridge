package dev.autobridge.car

import dev.autobridge.car.HomeDashboardTheme.Dp
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

/** Everything on the dashboard a tap can land on. */
internal enum class HomeRegion {
    CONTINUE, CONTROL_PREVIOUS, CONTROL_PLAY, CONTROL_NEXT, QUICK_ACCESS,
    RECENT_HEADER, RECENT_ITEM, QUEUE_HEADER, SCROLL_UP, SCROLL_DOWN
}

/** A resolved tap: which region, and which item of it when the region is a list. */
internal data class HomeHit(val region: HomeRegion, val index: Int = -1)

/** Where the parts of the Now Playing card sit. Every box is in surface pixels, at scroll 0. */
internal data class HeroBoxes(
    val card: MenuBox,
    val art: MenuBox,
    /** Caption and title, to the right of [art]. */
    val text: MenuBox,
    val progress: MenuBox,
    /** Elapsed time on the left, duration on the right, under [progress]. */
    val times: MenuBox,
    val previous: MenuBox,
    val play: MenuBox,
    val next: MenuBox
)

/**
 * Responsive geometry for the car home, computed from the area the host leaves free.
 *
 * The design is drawn at one size (1280 × 720): the AutoBridge header, then a row holding the Now
 * Playing card on the left and the 3 × 2 quick-access grid on the right, both 274dp tall, then
 * "Recently sent from phone" with its queue link and up to three sent items side by side. Rather
 * than re-deciding each piece per head unit, the whole column is laid out at that design size and
 * scaled by one factor so it fills the free height, which keeps every proportion the design fixes.
 *
 * The factor is clamped ([HomeDashboardTheme.MIN_SCALE]..[HomeDashboardTheme.MAX_SCALE]): a large
 * screen gets breathing room instead of giant cards, and a small one keeps its touch targets and
 * scrolls the column with the [scrollUp] / [scrollDown] buttons rather than shrinking past them.
 *
 * Widths follow what is there. A unit too narrow for the card grid beside the Now Playing card
 * stacks the card above the grid; one too narrow for three sent items across stacks those too;
 * an ultra-wide one centres the column at [Dp.MAX_CONTENT_WIDTH]. A section with no content is
 * absent and its space is simply not drawn: no empty frames.
 */
internal class HomeDashboardLayout private constructor(
    val density: Float,
    /** dp → px on this layout: the head unit's density times the fit scale. */
    val unit: Float,
    val logo: MenuBox,
    /** Title start; it is centred on [logo] vertically by the renderer, which owns the font metrics. */
    val titleX: Float,
    val titleSize: Float,
    /** Clip region of the scrolling column; equal to the content bounds unless scrolling is needed. */
    val viewport: MenuBox,
    val hero: HeroBoxes?,
    /** Card bounds at scroll offset 0, row-major. */
    val cards: List<MenuBox>,
    /** "Recently sent from phone": the label, sized to its text so the rest of the line is not a target. */
    val recentHeader: MenuBox?,
    /** "Queue N items", right-aligned on the same line. */
    val queueHeader: MenuBox?,
    val recentRows: List<MenuBox>,
    val maxScroll: Float,
    val scrollUp: MenuBox?,
    val scrollDown: MenuBox?,
    val labelSize: Float
) {
    val scrollable: Boolean get() = maxScroll > 0f

    /** The Now Playing card, which the hit test and the log talk about on their own. */
    val continueCard: MenuBox? get() = hero?.card

    /** [value] dp on this layout, scale included. */
    fun dp(value: Float) = value * unit

    /**
     * What a tap at ([x], [y]) hits with the column at [scroll], or null for empty space.
     *
     * The Now Playing card's buttons sit inside the card, so they are tried first; everything
     * else is disjoint.
     */
    fun hit(x: Float, y: Float, scroll: Float): HomeHit? {
        // The scroll buttons sit in their own lane outside the viewport and never move with it.
        if (scrollUp?.contains(x, y) == true) return HomeHit(HomeRegion.SCROLL_UP)
        if (scrollDown?.contains(x, y) == true) return HomeHit(HomeRegion.SCROLL_DOWN)
        if (!viewport.contains(x, y)) return null

        hero?.let {
            if (it.previous.offset(-scroll).contains(x, y)) return HomeHit(HomeRegion.CONTROL_PREVIOUS)
            if (it.play.offset(-scroll).contains(x, y)) return HomeHit(HomeRegion.CONTROL_PLAY)
            if (it.next.offset(-scroll).contains(x, y)) return HomeHit(HomeRegion.CONTROL_NEXT)
            if (it.card.offset(-scroll).contains(x, y)) return HomeHit(HomeRegion.CONTINUE)
        }
        cards.forEachIndexed { index, box ->
            if (box.offset(-scroll).contains(x, y)) return HomeHit(HomeRegion.QUICK_ACCESS, index)
        }
        recentHeader?.offset(-scroll)?.let { if (it.contains(x, y)) return HomeHit(HomeRegion.RECENT_HEADER) }
        queueHeader?.offset(-scroll)?.let { if (it.contains(x, y)) return HomeHit(HomeRegion.QUEUE_HEADER) }
        recentRows.forEachIndexed { index, box ->
            if (box.offset(-scroll).contains(x, y)) return HomeHit(HomeRegion.RECENT_ITEM, index)
        }
        return null
    }

    /** One card row plus its gap: what a single scroll-button tap moves. */
    val scrollStep: Float
        get() = if (cards.size > COLUMNS) cards[COLUMNS].top - cards[0].top else viewport.height / 2f

    companion object {
        const val COLUMNS = 3

        /** Most sent items the row shows; the rest are on the Recently Sent screen. */
        const val MAX_RECENT = 3

        /** Line box of a text run as a multiple of its size; roughly ascent+descent for this family. */
        const val LINE_RATIO = 1.3f

        /** Hero and grid share one height: two cards and the gap between them. */
        private const val TOP_ROW = Dp.CARD_HEIGHT * 2 + Dp.GAP

        /**
         * @param safe area not covered by host chrome (visible/stable area), in surface pixels.
         * @param labels the quick-access card labels, in order.
         * @param recentTitle the Recently Sent label, so its tap target is the text and not the line.
         * @param queueLink the queue link's text, or null when the queue is empty.
         * @param measure width of a text at a size in pixels, from the real paint.
         */
        fun compute(
            safe: MenuBox,
            density: Float,
            labels: List<String>,
            content: HomeDashboardContent,
            measure: (String, Float) -> Float,
            recentTitle: String = "",
            queueLink: String? = null
        ): HomeDashboardLayout {
            val d = density
            val hasHero = content.hasContinueWatching
            val recentCount = min(content.recentlySent.size, MAX_RECENT)
            val hasQueueLink = !queueLink.isNullOrBlank() && content.queueTotal > 0
            val hasSection = recentCount > 0 || hasQueueLink
            val gridRows = (labels.size + COLUMNS - 1) / COLUMNS
            val gridHeight = gridRows * Dp.CARD_HEIGHT + (gridRows - 1) * Dp.GAP

            val usable = max(1f, safe.height - (Dp.TOP_PADDING + Dp.BOTTOM_PADDING) * d)

            /** Arrangement decided for one scale; the column's height in dp follows from it. */
            data class Arrangement(val heroBeside: Boolean, val rowsAcross: Boolean)

            fun heightDp(a: Arrangement): Float {
                var h = Dp.HEADER + Dp.GAP
                h += when {
                    !hasHero -> gridHeight
                    a.heroBeside -> max(TOP_ROW, gridHeight)
                    else -> TOP_ROW + Dp.GAP + gridHeight
                }
                if (hasSection) {
                    h += Dp.GAP + Dp.SECTION_TITLE * LINE_RATIO
                    if (recentCount > 0) {
                        h += Dp.SECTION_TITLE_GAP +
                            if (a.rowsAcross) Dp.ROW_HEIGHT
                            else recentCount * Dp.ROW_HEIGHT + (recentCount - 1) * Dp.GAP
                    }
                }
                return h
            }

            fun columnWidth(unit: Float, scrolling: Boolean): Float {
                val lane = if (scrolling) (Dp.SCROLL_BUTTON + Dp.SCROLL_COLUMN_GAP) * d else 0f
                return min(safe.width - Dp.MARGIN * unit * 2 - lane, Dp.MAX_CONTENT_WIDTH * unit)
            }

            fun arrangementAt(unit: Float, scrolling: Boolean): Arrangement {
                val width = columnWidth(unit, scrolling)
                val gridBeside = width - (Dp.HERO_WIDTH + Dp.GAP) * unit
                val heroBeside = !hasHero ||
                    (gridBeside - Dp.GAP * unit * (COLUMNS - 1)) / COLUMNS >= Dp.CARD_MIN_WIDTH * unit
                val rowsAcross = recentCount <= 1 ||
                    (width - Dp.GAP * unit * (recentCount - 1)) / recentCount >= Dp.ROW_MIN_WIDTH * unit
                return Arrangement(heroBeside, rowsAcross)
            }

            /** What the column needs across at scale 1: the design's card width, not the minimum. */
            fun widthDp(a: Arrangement): Float =
                Dp.MARGIN * 2 + (if (hasHero && a.heroBeside) Dp.HERO_WIDTH + Dp.GAP else 0f) +
                    COLUMNS * Dp.CARD_WIDTH + (COLUMNS - 1) * Dp.GAP

            // One scale for the whole column: whichever of the free height and width is tighter,
            // so a 1280 x 720 unit gets the design as drawn and a bigger one grows it evenly.
            fun fit(a: Arrangement, scrolling: Boolean): Float {
                val lane = if (scrolling) (Dp.SCROLL_BUTTON + Dp.SCROLL_COLUMN_GAP) * d else 0f
                val byHeight = usable / (heightDp(a) * d)
                val byWidth = (safe.width - lane) / (widthDp(a) * d)
                return min(byHeight, byWidth).coerceIn(HomeDashboardTheme.MIN_SCALE, HomeDashboardTheme.MAX_SCALE)
            }

            // The arrangement depends on the scale and the scale on the arrangement (stacking makes
            // the column taller and narrower); one correction pass settles it.
            var scale = fit(Arrangement(heroBeside = true, rowsAcross = true), scrolling = false)
            var arrangement = arrangementAt(scale * d, scrolling = false)
            scale = fit(arrangement, scrolling = false)
            arrangement = arrangementAt(scale * d, scrolling = false)
            val scrolling = heightDp(arrangement) * scale * d > usable + 0.5f
            if (scrolling) {
                scale = fit(arrangement, scrolling = true)
                arrangement = arrangementAt(scale * d, scrolling = true)
            }

            val u = scale * d
            val gap = Dp.GAP * u
            val top = safe.top + Dp.TOP_PADDING * d
            val bottom = safe.bottom - Dp.BOTTOM_PADDING * d
            val margin = Dp.MARGIN * u
            val lane = if (scrolling) (Dp.SCROLL_BUTTON + Dp.SCROLL_COLUMN_GAP) * d else 0f
            val width = columnWidth(u, scrolling)
            val areaLeft = safe.left + margin + lane
            val areaRight = safe.right - margin
            val left = areaLeft + (areaRight - areaLeft - width) / 2f
            val right = left + width

            // --- header ---------------------------------------------------------------------
            val logoSize = Dp.LOGO * u
            val headerTop = top + (Dp.HEADER * u - logoSize) / 2f
            val logo = MenuBox(left, headerTop, left + logoSize, headerTop + logoSize)
            var cursor = top + Dp.HEADER * u + gap

            // --- Now Playing + grid ---------------------------------------------------------
            var hero: HeroBoxes? = null
            val gridLeft: Float
            val gridTop: Float
            if (hasHero && arrangement.heroBeside) {
                hero = heroBoxes(MenuBox(left, cursor, left + Dp.HERO_WIDTH * u, cursor + TOP_ROW * u), u)
                gridLeft = hero.card.right + gap
                gridTop = cursor
                cursor += max(TOP_ROW, gridHeight) * u
            } else if (hasHero) {
                hero = heroBoxes(MenuBox(left, cursor, right, cursor + TOP_ROW * u), u)
                cursor = hero.card.bottom + gap
                gridLeft = left
                gridTop = cursor
                cursor += gridHeight * u
            } else {
                gridLeft = left
                gridTop = cursor
                cursor += gridHeight * u
            }
            val cardWidth = (right - gridLeft - gap * (COLUMNS - 1)) / COLUMNS
            val cardHeight = Dp.CARD_HEIGHT * u
            val cards = labels.indices.map { index ->
                val cardLeft = gridLeft + (index % COLUMNS) * (cardWidth + gap)
                val cardTop = gridTop + (index / COLUMNS) * (cardHeight + gap)
                MenuBox(cardLeft, cardTop, cardLeft + cardWidth, cardTop + cardHeight)
            }

            // One label size for every card, so "TV" and "YouTube Music" read as one component.
            val labelMaxWidth = cardWidth - Dp.CARD_PADDING * u * 2
            val labelFloor = Dp.LABEL_MIN * u
            var labelSize = Dp.LABEL * u
            while (labelSize > labelFloor && labels.any { measure(it, labelSize) > labelMaxWidth }) {
                labelSize -= 0.5f * d
            }
            labelSize = max(labelSize, labelFloor)

            // --- Recently sent from phone ----------------------------------------------------
            var recentHeader: MenuBox? = null
            var queueHeader: MenuBox? = null
            var recentRows: List<MenuBox> = emptyList()
            if (hasSection) {
                cursor += gap
                val line = Dp.SECTION_TITLE * LINE_RATIO * u
                // Generous vertical slop on the two header targets: the text is small, the finger is not.
                val slop = (line * 0.5f).coerceAtMost(gap / 2f)
                if (recentCount > 0) {
                    val textWidth = measure(recentTitle, Dp.SECTION_TITLE * u)
                    recentHeader = MenuBox(left, cursor - slop, left + textWidth, cursor + line + slop)
                }
                if (hasQueueLink) {
                    val linkWidth = measure(queueLink!!, Dp.SECTION_LINK * u)
                    queueHeader = MenuBox(right - linkWidth, cursor - slop, right, cursor + line + slop)
                }
                cursor += line
                if (recentCount > 0) {
                    cursor += Dp.SECTION_TITLE_GAP * u
                    val rowHeight = Dp.ROW_HEIGHT * u
                    recentRows = if (arrangement.rowsAcross) {
                        val rowWidth = (width - gap * (recentCount - 1)) / recentCount
                        (0 until recentCount).map { i ->
                            val rowLeft = left + i * (rowWidth + gap)
                            MenuBox(rowLeft, cursor, rowLeft + rowWidth, cursor + rowHeight)
                        }.also { cursor += rowHeight }
                    } else {
                        (0 until recentCount).map { i ->
                            val rowTop = cursor + i * (rowHeight + gap)
                            MenuBox(left, rowTop, right, rowTop + rowHeight)
                        }.also { cursor = it.last().bottom }
                    }
                }
            }

            val contentTop = logo.bottom.coerceAtLeast(top + Dp.HEADER * u)
            val contentBottom = cursor
            val maxScroll = if (scrolling) max(0f, contentBottom - bottom) else 0f
            // Everything under the header scrolls; the header itself stays put.
            val viewport = MenuBox(left, contentTop, right, if (scrolling) bottom else contentBottom)

            val buttonSize = Dp.SCROLL_BUTTON * d
            val buttonLeft = safe.left + margin
            val scrollUp = if (maxScroll > 0f) {
                MenuBox(buttonLeft, viewport.top + gap, buttonLeft + buttonSize, viewport.top + gap + buttonSize)
            } else null
            val scrollDown = if (maxScroll > 0f) {
                MenuBox(buttonLeft, viewport.bottom - buttonSize, buttonLeft + buttonSize, viewport.bottom)
            } else null

            return HomeDashboardLayout(
                density = d,
                unit = u,
                logo = logo,
                titleX = logo.right + Dp.LOGO_TO_TITLE * u,
                titleSize = Dp.TITLE * u,
                viewport = viewport,
                hero = hero,
                cards = cards,
                recentHeader = recentHeader,
                queueHeader = queueHeader,
                recentRows = recentRows,
                maxScroll = maxScroll,
                scrollUp = scrollUp,
                scrollDown = scrollDown,
                labelSize = labelSize
            )
        }

        /** The card's insides: art and text on top, progress and times, then the three controls. */
        private fun heroBoxes(card: MenuBox, u: Float): HeroBoxes {
            val pad = Dp.HERO_PADDING * u
            val inner = Dp.HERO_INNER_GAP * u
            val art = MenuBox(card.left + pad, card.top + pad, card.left + pad + Dp.HERO_ART * u, card.top + pad + Dp.HERO_ART * u)
            val text = MenuBox(art.right + inner, art.top, card.right - pad, art.bottom)
            val progressTop = art.bottom + inner
            val progress = MenuBox(card.left + pad, progressTop, card.right - pad, progressTop + Dp.HERO_PROGRESS * u)
            val timesTop = progress.bottom + Dp.HERO_PROGRESS * u
            val times = MenuBox(progress.left, timesTop, progress.right, timesTop + Dp.HERO_TIME * LINE_RATIO * u)

            val primary = Dp.CONTROL_PRIMARY * u
            val side = Dp.CONTROL * u
            val centerY = card.bottom - pad - primary / 2f
            val play = MenuBox(card.centerX - primary / 2f, centerY - primary / 2f, card.centerX + primary / 2f, centerY + primary / 2f)
            val previous = MenuBox(card.left + pad, centerY - side / 2f, card.left + pad + side, centerY + side / 2f)
            val next = MenuBox(card.right - pad - side, centerY - side / 2f, card.right - pad, centerY + side / 2f)
            return HeroBoxes(card, art, text, progress, times, previous, play, next)
        }
    }
}
