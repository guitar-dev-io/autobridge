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
 * Playing card on the left and the 3 × 2 quick-access grid on the right, both 420dp tall, then
 * "Recently sent from phone" with the queue button and up to three sent items side by side. Rather
 * than re-deciding each piece per head unit, the whole column is laid out at that design size and
 * scaled by one factor, which keeps every proportion the design fixes. A unit too short for that
 * without scrolling gets [HomeDashboardTheme.COMPACT] instead (see [HomeDashboardTheme.Profile]).
 *
 * The factor is clamped ([HomeDashboardTheme.MIN_SCALE]..[HomeDashboardTheme.MAX_SCALE]): a large
 * screen gets breathing room instead of giant cards, and a small one keeps its touch targets and
 * scrolls the column with the [scrollUp] / [scrollDown] buttons rather than shrinking past them.
 *
 * Nothing moves when there is nothing to show. The Now Playing slot is always there, holding an
 * empty-state card ([emptyHero]) when no session exists, so the quick-access cards sit in the same
 * place every time and a driver can reach for them without looking; the sent-items row shows a
 * placeholder ([emptyRecent]) rather than collapsing.
 *
 * A unit too narrow for the grid beside the card stacks the card above it; one too narrow for
 * three sent items across stacks those too; an ultra-wide one centres the column at
 * [Dp.MAX_CONTENT_WIDTH].
 */
internal class HomeDashboardLayout private constructor(
    /** Which set of sizes this layout was built with; the renderer draws to the same one. */
    val profile: HomeDashboardTheme.Profile,
    val density: Float,
    /** dp → px on this layout: the head unit's density times the fit scale. */
    val unit: Float,
    val logo: MenuBox,
    /** Title start; it is centred on [logo] vertically by the renderer, which owns the font metrics. */
    val titleX: Float,
    val titleSize: Float,
    /** Clip region of the scrolling column; equal to the content bounds unless scrolling is needed. */
    val viewport: MenuBox,
    /** The Now Playing card, when there is a session to show. */
    val hero: HeroBoxes?,
    /** The same slot when there is none: "Nothing playing yet". Not a tap target. */
    val emptyHero: MenuBox?,
    /** Card bounds at scroll offset 0, row-major. */
    val cards: List<MenuBox>,
    /** The section title. Always drawn; a tap target only when there are sent items to open. */
    val sectionTitle: MenuBox,
    /** "Recently sent from phone ›" as a tap target, or null when nothing has been sent. */
    val recentHeader: MenuBox?,
    /** The queue button, right-aligned on the section line, when the queue has items. */
    val queueHeader: MenuBox?,
    /** The same place showing "Queue empty", drawn but not tappable. */
    val queueEmpty: MenuBox?,
    val recentRows: List<MenuBox>,
    /** Placeholder row when nothing has been sent yet. */
    val emptyRecent: MenuBox?,
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

        /**
         * @param safe area not covered by host chrome (visible/stable area), in surface pixels.
         * @param labels the quick-access card labels, in order.
         * @param recentTitle the section title, so its tap target is the text and not the line.
         * @param queueLabel the queue button's text: "Queue · N items", or "Queue empty".
         * @param measure width of a bold text at a size in pixels, from the real paint.
         */
        fun compute(
            safe: MenuBox,
            density: Float,
            labels: List<String>,
            content: HomeDashboardContent,
            measure: (String, Float) -> Float,
            recentTitle: String = "",
            queueLabel: String = ""
        ): HomeDashboardLayout {
            // The roomy design whenever it fits; a unit too short for it gets the compact one
            // rather than a scrolling column, since what scrolls off is what a driver cannot see.
            val roomy = place(HomeDashboardTheme.ROOMY, safe, density, labels, content, measure, recentTitle, queueLabel)
            if (!roomy.scrollable) return roomy
            return place(HomeDashboardTheme.COMPACT, safe, density, labels, content, measure, recentTitle, queueLabel)
        }

        private fun place(
            profile: HomeDashboardTheme.Profile,
            safe: MenuBox,
            density: Float,
            labels: List<String>,
            content: HomeDashboardContent,
            measure: (String, Float) -> Float,
            recentTitle: String,
            queueLabel: String
        ): HomeDashboardLayout {
            val d = density
            val headerBand = profile.header + profile.headerGap
            val hasHero = content.hasContinueWatching
            val recentCount = min(content.recentlySent.size, MAX_RECENT)
            // The placeholder row stands in for the sent items; it is one row however wide.
            val rowSlots = max(recentCount, 1)
            // "Queue empty" means something only next to something else; on a fresh install,
            // with nothing played and nothing sent, the line is just the title.
            val showQueue = content.queueTotal > 0 || hasHero || recentCount > 0
            val gridRows = (labels.size + COLUMNS - 1) / COLUMNS
            val gridHeight = gridRows * profile.cardHeight + (gridRows - 1) * Dp.GAP

            val usable = max(1f, safe.height - (profile.topPadding + profile.bottomPadding) * d)

            /** Arrangement decided for one scale; the column's height in dp follows from it. */
            data class Arrangement(val heroBeside: Boolean, val rowsAcross: Boolean)

            fun heightDp(a: Arrangement): Float {
                var h = headerBand
                h += if (a.heroBeside) gridHeight else profile.heroStackedHeight + Dp.GAP + gridHeight
                h += Dp.GAP + Dp.SECTION_HEADER + Dp.SECTION_TITLE_GAP
                h += if (a.rowsAcross) Dp.ROW_HEIGHT else rowSlots * Dp.ROW_HEIGHT + (rowSlots - 1) * Dp.GAP
                return h
            }

            /** What the column needs across at scale 1: the design's card width, not the minimum. */
            fun widthDp(a: Arrangement): Float =
                Dp.MARGIN * 2 + (if (a.heroBeside) profile.heroWidth + Dp.GAP else 0f) +
                    COLUMNS * Dp.CARD_WIDTH + (COLUMNS - 1) * Dp.GAP

            fun columnWidth(unit: Float, scrolling: Boolean): Float {
                val lane = if (scrolling) (Dp.SCROLL_BUTTON + Dp.SCROLL_COLUMN_GAP) * d else 0f
                return min(safe.width - Dp.MARGIN * unit * 2 - lane, Dp.MAX_CONTENT_WIDTH * unit)
            }

            fun arrangementAt(unit: Float, scrolling: Boolean): Arrangement {
                val width = columnWidth(unit, scrolling)
                val gridBeside = width - (profile.heroWidth + Dp.GAP) * unit
                val heroBeside = (gridBeside - Dp.GAP * unit * (COLUMNS - 1)) / COLUMNS >= Dp.CARD_MIN_WIDTH * unit
                val rowsAcross = rowSlots <= 1 ||
                    (width - Dp.GAP * unit * (rowSlots - 1)) / rowSlots >= Dp.ROW_MIN_WIDTH * unit
                return Arrangement(heroBeside, rowsAcross)
            }

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
            val top = safe.top + profile.topPadding * d
            val bottom = safe.bottom - profile.bottomPadding * d
            val margin = Dp.MARGIN * u
            val lane = if (scrolling) (Dp.SCROLL_BUTTON + Dp.SCROLL_COLUMN_GAP) * d else 0f
            val width = columnWidth(u, scrolling)
            val areaLeft = safe.left + margin + lane
            val areaRight = safe.right - margin
            val left = areaLeft + (areaRight - areaLeft - width) / 2f
            val right = left + width

            // --- header ---------------------------------------------------------------------
            val logoSize = profile.logo * u
            val headerTop = top + (profile.header * u - logoSize) / 2f
            val logo = MenuBox(left, headerTop, left + logoSize, headerTop + logoSize)
            var cursor = top + headerBand * u

            // --- Now Playing (or its empty state) + grid -------------------------------------
            val slot: MenuBox
            val gridLeft: Float
            val gridTop: Float
            if (arrangement.heroBeside) {
                slot = MenuBox(left, cursor, left + profile.heroWidth * u, cursor + gridHeight * u)
                gridLeft = slot.right + gap
                gridTop = cursor
            } else {
                slot = MenuBox(left, cursor, right, cursor + profile.heroStackedHeight * u)
                gridLeft = left
                gridTop = slot.bottom + gap
            }
            val hero = if (hasHero) heroBoxes(slot, u, profile) else null
            val emptyHero = if (hasHero) null else slot
            cursor = gridTop + gridHeight * u

            val cardWidth = (right - gridLeft - gap * (COLUMNS - 1)) / COLUMNS
            val cardHeight = profile.cardHeight * u
            val cards = labels.indices.map { index ->
                val cardLeft = gridLeft + (index % COLUMNS) * (cardWidth + gap)
                val cardTop = gridTop + (index / COLUMNS) * (cardHeight + gap)
                MenuBox(cardLeft, cardTop, cardLeft + cardWidth, cardTop + cardHeight)
            }

            // One label size for every card, so "TV" and "YouTube Music" read as one component.
            val labelMaxWidth = cardWidth - profile.cardPadding * u * 2
            val labelFloor = Dp.LABEL_MIN * u
            var labelSize = profile.label * u
            while (labelSize > labelFloor && labels.any { measure(it, labelSize) > labelMaxWidth }) {
                labelSize -= 0.5f * d
            }
            labelSize = max(labelSize, labelFloor)

            // --- Recently sent from phone ----------------------------------------------------
            cursor += gap
            val lineTop = cursor
            val lineBottom = cursor + Dp.SECTION_HEADER * u
            val titleWidth = measure(recentTitle, Dp.SECTION_TITLE * u)
            val sectionTitle = MenuBox(left, lineTop, left + titleWidth, lineBottom)
            // The chevron is part of the target: it is what says the title opens something.
            val recentHeader = if (recentCount > 0) {
                sectionTitle.copy(right = sectionTitle.right + (Dp.SECTION_CHEVRON_GAP + Dp.SECTION_CHEVRON) * u)
            } else null
            var queueHeader: MenuBox? = null
            var queueEmpty: MenuBox? = null
            if (showQueue) {
                val pillWidth = if (content.queueTotal > 0) {
                    (Dp.QUEUE_PILL_PADDING_START + Dp.QUEUE_PILL_ICON + Dp.QUEUE_PILL_GAP * 2 +
                        Dp.QUEUE_PILL_CHEVRON + Dp.QUEUE_PILL_PADDING_END) * u +
                        measure(queueLabel, Dp.SECTION_LINK * u)
                } else {
                    (Dp.QUEUE_PILL_PADDING_START * 2 + Dp.QUEUE_PILL_ICON + Dp.QUEUE_PILL_GAP) * u +
                        measure(queueLabel, Dp.SECTION_LINK * u)
                }
                val pill = MenuBox(right - pillWidth, lineTop, right, lineBottom)
                if (content.queueTotal > 0) queueHeader = pill else queueEmpty = pill
            }
            cursor = lineBottom + Dp.SECTION_TITLE_GAP * u

            val rowHeight = Dp.ROW_HEIGHT * u
            var recentRows: List<MenuBox> = emptyList()
            var emptyRecent: MenuBox? = null
            if (recentCount > 0) {
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
            } else {
                emptyRecent = MenuBox(left, cursor, right, cursor + rowHeight)
                cursor += rowHeight
            }

            val contentTop = top + profile.header * u
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
                profile = profile,
                density = d,
                unit = u,
                logo = logo,
                titleX = logo.right + Dp.LOGO_TO_TITLE * u,
                titleSize = profile.title * u,
                viewport = viewport,
                hero = hero,
                emptyHero = emptyHero,
                cards = cards,
                sectionTitle = sectionTitle,
                recentHeader = recentHeader,
                queueHeader = queueHeader,
                queueEmpty = queueEmpty,
                recentRows = recentRows,
                emptyRecent = emptyRecent,
                maxScroll = maxScroll,
                scrollUp = scrollUp,
                scrollDown = scrollDown,
                labelSize = labelSize
            )
        }

        /**
         * The card's insides: art and text at the top; the progress bar, its times and the three
         * controls held together at the bottom, so a taller card opens up between the two groups
         * rather than pushing the controls away from the progress they belong to.
         */
        private fun heroBoxes(card: MenuBox, u: Float, profile: HomeDashboardTheme.Profile): HeroBoxes {
            val pad = profile.heroPadding * u
            val inner = profile.heroInnerGap * u
            val art = MenuBox(card.left + pad, card.top + pad, card.left + pad + profile.heroArt * u, card.top + pad + profile.heroArt * u)
            val text = MenuBox(art.right + inner, art.top, card.right - pad, art.bottom)

            val primary = profile.controlPrimary * u
            val side = profile.control * u
            val centerY = card.bottom - pad - primary / 2f
            val play = MenuBox(card.centerX - primary / 2f, centerY - primary / 2f, card.centerX + primary / 2f, centerY + primary / 2f)
            val previous = MenuBox(card.left + pad, centerY - side / 2f, card.left + pad + side, centerY + side / 2f)
            val next = MenuBox(card.right - pad - side, centerY - side / 2f, card.right - pad, centerY + side / 2f)

            val timesBottom = play.top - Dp.HERO_CONTROLS_GAP * u
            val times = MenuBox(card.left + pad, timesBottom - Dp.HERO_TIME * LINE_RATIO * u, card.right - pad, timesBottom)
            val barBottom = times.top - Dp.HERO_PROGRESS * u
            val progress = MenuBox(times.left, barBottom - Dp.HERO_PROGRESS * u, times.right, barBottom)
            return HeroBoxes(card, art, text, progress, times, previous, play, next)
        }
    }
}
