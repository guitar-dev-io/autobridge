package dev.autobridge.car

import dev.autobridge.car.HomeDashboardTheme.Dp
import dev.autobridge.design.HomeLayout
import kotlin.math.ceil
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
 * The compact layout's now-playing strip: art, title and source, and the play button. Every box is
 * in surface pixels, at scroll 0.
 */
internal data class StripBoxes(
    val card: MenuBox,
    val art: MenuBox,
    val text: MenuBox,
    val play: MenuBox
)

/**
 * Responsive geometry for the car home, computed from the area the host leaves free.
 *
 * Settings > Appearance picks one of three homes ([HomeDashboardLook.homeLayout]):
 * - [HomeLayout.DASHBOARD], described below: the Now Playing card beside the feature cards, then
 *   Recently Sent with the queue button.
 * - [HomeLayout.CLASSIC]: the feature tiles alone, two rows filling the screen ([placeClassic]).
 * - [HomeLayout.COMPACT]: two columns of feature rows and a now-playing strip under them
 *   ([placeCompact]).
 * All three keep the same header, hit regions, scroll buttons and focus, so the screen above does
 * not care which one it shows. Text is sized through [sp], which applies the look's capped font
 * scale on top of the layout's own scale; boxes that hold text grow with it.
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
    /** What this layout was built for; a different look needs a new layout. */
    val look: HomeDashboardLook,
    /** Which of the three homes this is; equal to the look's choice. */
    val kind: HomeLayout,
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
    /** The compact layout's now-playing strip, when there is a session to show. */
    val strip: StripBoxes?,
    /** The same strip when there is none: "Nothing playing yet". Not a tap target. */
    val emptyStrip: MenuBox?,
    /**
     * The Recently Sent section title (Dashboard only). Always drawn there; a tap target only when
     * there are sent items to open.
     */
    val sectionTitle: MenuBox?,
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
    val labelSize: Float,
    /** Cards per row: [columnsFor] the card count on the Dashboard. */
    val columns: Int,
    /** Icon chip of a feature card, in pixels. */
    val chipSize: Float
) {
    val scrollable: Boolean get() = maxScroll > 0f

    /** The Now Playing card, which the hit test and the log talk about on their own. */
    val continueCard: MenuBox? get() = hero?.card

    /** [value] dp on this layout, scale included. */
    fun dp(value: Float) = value * unit

    /** [value] dp of text: the look's capped font scale, then this layout's scale. */
    fun sp(value: Float) = look.text(value) * unit

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
        strip?.let {
            if (it.play.offset(-scroll).contains(x, y)) return HomeHit(HomeRegion.CONTROL_PLAY)
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
        get() = if (cards.size > columns) cards[columns].top - cards[0].top else viewport.height / 2f

    companion object {
        const val COLUMNS = 3

        /**
         * Cards per row. Six cards keep the 3 × 2 design; the sideload builds add Multi-app and
         * Desktop browser cards (CarExtensions), and eight go 4 × 2 rather than 3 + 3 + 2, so the
         * home keeps two card rows and the same height instead of every card shrinking for a third.
         */
        fun columnsFor(count: Int): Int = if (count > COLUMNS * 2) 4 else COLUMNS

        /**
         * Tiles per row on the Classic grid: two rows, four across at most (the design's 4 x 2),
         * never fewer than three so six features keep the 3 x 2 the Dashboard has.
         */
        fun classicColumnsFor(count: Int): Int = ((count + 1) / 2).coerceIn(COLUMNS, 4)

        /** The Compact layout's rows are always two across. */
        const val COMPACT_COLUMNS = 2

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
         * @param look the appearance to lay out for: which home, and the text scale.
         */
        fun compute(
            safe: MenuBox,
            density: Float,
            labels: List<String>,
            content: HomeDashboardContent,
            measure: (String, Float) -> Float,
            recentTitle: String = "",
            queueLabel: String = "",
            look: HomeDashboardLook = HomeDashboardLook.DEFAULT
        ): HomeDashboardLayout = when (look.homeLayout) {
            HomeLayout.CLASSIC -> placeClassic(look, safe, density, labels, measure)
            HomeLayout.COMPACT -> placeCompact(look, safe, density, labels, content, measure)
            HomeLayout.DASHBOARD -> {
                // The roomy design whenever it fits; a unit too short for it gets the compact one
                // rather than a scrolling column, since what scrolls off is what a driver cannot see.
                val roomy = placeDashboard(look, HomeDashboardTheme.ROOMY, safe, density, labels, content, measure, recentTitle, queueLabel)
                if (!roomy.scrollable) roomy
                else placeDashboard(look, HomeDashboardTheme.COMPACT, safe, density, labels, content, measure, recentTitle, queueLabel)
            }
        }

        private fun placeDashboard(
            look: HomeDashboardLook,
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
            val columns = columnsFor(labels.size)
            val gridRows = (labels.size + columns - 1) / columns
            val gridHeight = gridRows * profile.cardHeight + (gridRows - 1) * Dp.GAP

            val usable = max(1f, safe.height - (profile.topPadding + profile.bottomPadding) * d)

            /** Arrangement decided for one scale; the column's height in dp follows from it. */
            data class Arrangement(val heroBeside: Boolean, val rowsAcross: Boolean)

            // Nothing playing, on the compact profile, with the card stacked above the grid: the
            // placeholder would take a whole screen height of a short unit (eight cards are two
            // 128 dp rows on their own) and push every card below the fold, so it gives way. It
            // only says "nothing yet"; the grid is what a driver needs to reach.
            val stackedHero = if (!hasHero && profile === HomeDashboardTheme.COMPACT) 0f else profile.heroStackedHeight
            val stackedGap = if (stackedHero > 0f) Dp.GAP else 0f

            fun heightDp(a: Arrangement): Float {
                var h = headerBand
                h += if (a.heroBeside) gridHeight else stackedHero + stackedGap + gridHeight
                h += Dp.GAP + Dp.SECTION_HEADER + Dp.SECTION_TITLE_GAP
                h += if (a.rowsAcross) Dp.ROW_HEIGHT else rowSlots * Dp.ROW_HEIGHT + (rowSlots - 1) * Dp.GAP
                return h
            }

            /** What the column needs across at scale 1: the design's card width, not the minimum. */
            fun widthDp(a: Arrangement): Float =
                Dp.MARGIN * 2 + (if (a.heroBeside) profile.heroWidth + Dp.GAP else 0f) +
                    columns * Dp.CARD_WIDTH + (columns - 1) * Dp.GAP

            fun columnWidth(unit: Float, scrolling: Boolean): Float {
                val lane = if (scrolling) (Dp.SCROLL_BUTTON + Dp.SCROLL_COLUMN_GAP) * d else 0f
                return min(safe.width - Dp.MARGIN * unit * 2 - lane, Dp.MAX_CONTENT_WIDTH * unit)
            }

            fun arrangementAt(unit: Float, scrolling: Boolean): Arrangement {
                val width = columnWidth(unit, scrolling)
                val gridBeside = width - (profile.heroWidth + Dp.GAP) * unit
                val heroBeside = (gridBeside - Dp.GAP * unit * (columns - 1)) / columns >= Dp.CARD_MIN_WIDTH * unit
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
                slot = MenuBox(left, cursor, right, cursor + stackedHero * u)
                gridLeft = left
                gridTop = slot.bottom + stackedGap * u
            }
            val hero = if (hasHero) heroBoxes(slot, u, profile, look) else null
            val emptyHero = if (hasHero || stackedHero == 0f && !arrangement.heroBeside) null else slot
            cursor = gridTop + gridHeight * u

            val cardWidth = (right - gridLeft - gap * (columns - 1)) / columns
            val cardHeight = profile.cardHeight * u
            val cards = labels.indices.map { index ->
                val cardLeft = gridLeft + (index % columns) * (cardWidth + gap)
                val cardTop = gridTop + (index / columns) * (cardHeight + gap)
                MenuBox(cardLeft, cardTop, cardLeft + cardWidth, cardTop + cardHeight)
            }

            // One label size for every card, so "TV" and "YouTube Music" read as one component.
            val labelMaxWidth = cardWidth - profile.cardPadding * u * 2
            val labelSize = fitLabel(labels, measure, look.text(profile.label) * u, look.text(Dp.LABEL_MIN) * u, labelMaxWidth, d)

            // --- Recently sent from phone ----------------------------------------------------
            cursor += gap
            val lineTop = cursor
            val lineBottom = cursor + Dp.SECTION_HEADER * u
            val titleWidth = measure(recentTitle, look.text(Dp.SECTION_TITLE) * u)
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
                        measure(queueLabel, look.text(Dp.SECTION_LINK) * u)
                } else {
                    (Dp.QUEUE_PILL_PADDING_START * 2 + Dp.QUEUE_PILL_ICON + Dp.QUEUE_PILL_GAP) * u +
                        measure(queueLabel, look.text(Dp.SECTION_LINK) * u)
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
                look = look,
                kind = HomeLayout.DASHBOARD,
                profile = profile,
                density = d,
                unit = u,
                logo = logo,
                titleX = logo.right + Dp.LOGO_TO_TITLE * u,
                titleSize = look.text(profile.title) * u,
                viewport = viewport,
                hero = hero,
                emptyHero = emptyHero,
                cards = cards,
                strip = null,
                emptyStrip = null,
                sectionTitle = sectionTitle,
                recentHeader = recentHeader,
                queueHeader = queueHeader,
                queueEmpty = queueEmpty,
                recentRows = recentRows,
                emptyRecent = emptyRecent,
                maxScroll = maxScroll,
                scrollUp = scrollUp,
                scrollDown = scrollDown,
                labelSize = labelSize,
                columns = columns,
                chipSize = profile.icon * u
            )
        }

        /** The largest label size from [start] down to [floor] at which every label fits [maxWidth]. */
        private fun fitLabel(
            labels: List<String>,
            measure: (String, Float) -> Float,
            start: Float,
            floor: Float,
            maxWidth: Float,
            d: Float
        ): Float {
            // One label size for every card, so "TV" and "YouTube Music" read as one component.
            var size = start
            while (size > floor && labels.any { measure(it, size) > maxWidth }) size -= 0.5f * d
            return max(size, floor)
        }

        /**
         * What Classic and Compact share: one scale from the design size, the header, the column's
         * edges and the area under the header. Neither has a Now Playing card to keep beside the
         * grid, so unlike the Dashboard they fill the height they get rather than scaling to it.
         */
        private class Frame(safe: MenuBox, val density: Float, val scrolling: Boolean) {
            val profile = if (safe.height / density < ROOMY_MIN_HEIGHT) HomeDashboardTheme.COMPACT else HomeDashboardTheme.ROOMY
            val u = density * min(safe.width / (DESIGN_WIDTH * density), safe.height / (DESIGN_HEIGHT * density))
                .coerceIn(HomeDashboardTheme.MIN_SCALE, HomeDashboardTheme.MAX_SCALE)
            val top = safe.top + profile.topPadding * density
            val bottom = safe.bottom - profile.bottomPadding * density
            val lane = if (scrolling) (Dp.SCROLL_BUTTON + Dp.SCROLL_COLUMN_GAP) * density else 0f
            val margin = Dp.MARGIN * u
            val width = min(safe.width - margin * 2 - lane, Dp.MAX_CONTENT_WIDTH * u)
            val left = safe.left + margin + lane + (safe.width - margin * 2 - lane - width) / 2f
            val right = left + width
            val buttonLeft = safe.left + margin
            val logo: MenuBox = run {
                val size = profile.logo * u
                val headerTop = top + (profile.header * u - size) / 2f
                MenuBox(left, headerTop, left + size, headerTop + size)
            }
            val contentTop = top + profile.header * u
            /** Where the cards start: under the header and its gap. */
            val areaTop = top + (profile.header + profile.headerGap) * u
        }

        /** Scroll buttons and viewport for a column of [contentBottom], as the Dashboard has them. */
        private fun scrollParts(frame: Frame, contentBottom: Float): Triple<MenuBox, MenuBox?, MenuBox?> {
            val maxScroll = if (frame.scrolling) max(0f, contentBottom - frame.bottom) else 0f
            val viewport = MenuBox(frame.left, frame.contentTop, frame.right, if (frame.scrolling) frame.bottom else contentBottom)
            if (maxScroll <= 0f) return Triple(viewport, null, null)
            val size = Dp.SCROLL_BUTTON * frame.density
            val gap = Dp.GAP * frame.u
            return Triple(
                viewport,
                MenuBox(frame.buttonLeft, viewport.top + gap, frame.buttonLeft + size, viewport.top + gap + size),
                MenuBox(frame.buttonLeft, viewport.bottom - size, frame.buttonLeft + size, viewport.bottom)
            )
        }

        /**
         * Classic: the feature tiles alone, [classicColumnsFor] across and two rows, each an icon
         * chip over its label, centred. The tiles share the free height; a unit too short for
         * [Dp.CLASSIC_TILE_MIN] tiles scrolls instead. Hiding labels (Icons & cards) leaves the
         * chip alone in the tile, which is the only place the setting applies: a Dashboard card
         * or a Compact row without its name would be a guess.
         */
        private fun placeClassic(
            look: HomeDashboardLook,
            safe: MenuBox,
            density: Float,
            labels: List<String>,
            measure: (String, Float) -> Float
        ): HomeDashboardLayout {
            val columns = classicColumnsFor(labels.size)
            val rows = max(1, ceil(labels.size / columns.toFloat()).toInt())

            fun tileHeight(frame: Frame): Float {
                val gap = Dp.GAP * frame.u
                return (frame.bottom - frame.areaTop - gap * (rows - 1)) / rows
            }

            var frame = Frame(safe, density, scrolling = false)
            val minTile = { f: Frame -> max(Dp.CLASSIC_TILE_MIN * f.u, classicContentHeight(look, f.u)) }
            if (tileHeight(frame) < minTile(frame)) frame = Frame(safe, density, scrolling = true)
            val u = frame.u
            val gap = Dp.GAP * u
            val tileH = max(tileHeight(frame), minTile(frame))
            val tileW = (frame.width - gap * (columns - 1)) / columns
            val cards = labels.indices.map { index ->
                val l = frame.left + (index % columns) * (tileW + gap)
                val t = frame.areaTop + (index / columns) * (tileH + gap)
                MenuBox(l, t, l + tileW, t + tileH)
            }
            val labelLine = if (look.showTileLabels) look.text(Dp.CLASSIC_LABEL) * LINE_RATIO * u + Dp.CLASSIC_LABEL_GAP * u else 0f
            val chip = (tileH - labelLine - Dp.CLASSIC_PADDING * u * 2).coerceIn(Dp.CLASSIC_CHIP_MIN * u, Dp.CLASSIC_CHIP * u)
            val labelSize = fitLabel(
                labels, measure, look.text(Dp.CLASSIC_LABEL) * u, look.text(Dp.LABEL_MIN) * u,
                tileW - Dp.CLASSIC_PADDING * u * 2, density
            )
            val contentBottom = cards.lastOrNull()?.bottom ?: frame.areaTop
            val (viewport, up, down) = scrollParts(frame, contentBottom)
            return HomeDashboardLayout(
                look = look, kind = HomeLayout.CLASSIC, profile = frame.profile, density = density, unit = u,
                logo = frame.logo, titleX = frame.logo.right + Dp.LOGO_TO_TITLE * u,
                titleSize = look.text(frame.profile.title) * u, viewport = viewport,
                hero = null, emptyHero = null, cards = cards, strip = null, emptyStrip = null,
                sectionTitle = null, recentHeader = null, queueHeader = null, queueEmpty = null,
                recentRows = emptyList(), emptyRecent = null,
                maxScroll = if (frame.scrolling) max(0f, contentBottom - frame.bottom) else 0f,
                scrollUp = up, scrollDown = down, labelSize = labelSize, columns = columns, chipSize = chip
            )
        }

        /** The least a Classic tile needs to hold its chip and its label at the look's text size. */
        private fun classicContentHeight(look: HomeDashboardLook, u: Float): Float {
            val label = if (look.showTileLabels) look.text(Dp.CLASSIC_LABEL) * LINE_RATIO + Dp.CLASSIC_LABEL_GAP else 0f
            return (Dp.CLASSIC_CHIP_MIN + label + Dp.CLASSIC_PADDING * 2) * u
        }

        /**
         * Compact: the features as rows two across (chip, name, chevron), and a now-playing strip
         * under them holding the session's art, title and play button, or an outline saying
         * nothing is playing. Rows share the height left above the strip up to
         * [Dp.COMPACT_ROW_MAX]; the strip stays on the bottom edge. A unit too short for
         * [Dp.COMPACT_ROW_MIN] rows scrolls, the strip following the last row.
         */
        private fun placeCompact(
            look: HomeDashboardLook,
            safe: MenuBox,
            density: Float,
            labels: List<String>,
            content: HomeDashboardContent,
            measure: (String, Float) -> Float
        ): HomeDashboardLayout {
            val columns = COMPACT_COLUMNS
            val rows = max(1, ceil(labels.size / columns.toFloat()).toInt())

            fun stripHeight(u: Float) =
                max(Dp.COMPACT_STRIP * u, (look.text(Dp.COMPACT_TITLE) + look.text(Dp.COMPACT_META)) * LINE_RATIO * u + 16f * u)
            fun minRow(u: Float) = max(Dp.COMPACT_ROW_MIN * u, look.text(Dp.COMPACT_TITLE) * LINE_RATIO * u + 20f * u)
            fun rowHeight(f: Frame): Float {
                val rowsArea = f.bottom - f.areaTop - stripHeight(f.u) - Dp.COMPACT_STRIP_GAP * f.u
                return (rowsArea - Dp.COMPACT_ROW_GAP * f.u * (rows - 1)) / rows
            }

            var frame = Frame(safe, density, scrolling = false)
            if (rowHeight(frame) < minRow(frame.u)) frame = Frame(safe, density, scrolling = true)
            val u = frame.u
            val rowH = rowHeight(frame).coerceIn(minRow(u), max(minRow(u), Dp.COMPACT_ROW_MAX * u))
            val columnGap = Dp.COMPACT_COLUMN_GAP * u
            val rowGap = Dp.COMPACT_ROW_GAP * u
            val rowW = (frame.width - columnGap * (columns - 1)) / columns
            val cards = labels.indices.map { index ->
                val l = frame.left + (index % columns) * (rowW + columnGap)
                val t = frame.areaTop + (index / columns) * (rowH + rowGap)
                MenuBox(l, t, l + rowW, t + rowH)
            }
            val rowsBottom = cards.lastOrNull()?.bottom ?: frame.areaTop
            val stripH = stripHeight(u)
            val stripTop = if (frame.scrolling) rowsBottom + Dp.COMPACT_STRIP_GAP * u else frame.bottom - stripH
            val stripBox = MenuBox(frame.left, stripTop, frame.right, stripTop + stripH)
            val strip = if (content.hasContinueWatching) stripBoxes(stripBox, u) else null

            val chip = Dp.COMPACT_CHIP * u
            val chevron = Dp.COMPACT_CHEVRON * u
            val labelSize = fitLabel(
                labels, measure, look.text(Dp.COMPACT_TITLE) * u, look.text(Dp.LABEL_MIN) * u,
                rowW - (Dp.COMPACT_ROW_PADDING * 2 + Dp.COMPACT_ROW_GAP_INNER * 2) * u - chip - chevron, density
            )
            val contentBottom = stripBox.bottom
            val (viewport, up, down) = scrollParts(frame, contentBottom)
            return HomeDashboardLayout(
                look = look, kind = HomeLayout.COMPACT, profile = frame.profile, density = density, unit = u,
                logo = frame.logo, titleX = frame.logo.right + Dp.LOGO_TO_TITLE * u,
                titleSize = look.text(frame.profile.title) * u, viewport = viewport,
                hero = null, emptyHero = null, cards = cards,
                strip = strip, emptyStrip = if (strip == null) stripBox else null,
                sectionTitle = null, recentHeader = null, queueHeader = null, queueEmpty = null,
                recentRows = emptyList(), emptyRecent = null,
                maxScroll = if (frame.scrolling) max(0f, contentBottom - frame.bottom) else 0f,
                scrollUp = up, scrollDown = down, labelSize = labelSize, columns = columns, chipSize = chip
            )
        }

        private fun stripBoxes(card: MenuBox, u: Float): StripBoxes {
            val art = Dp.COMPACT_STRIP_ART * u
            val artLeft = card.left + 12f * u
            val artBox = MenuBox(artLeft, card.centerY - art / 2f, artLeft + art, card.centerY + art / 2f)
            val play = Dp.COMPACT_STRIP_PLAY * u
            val playRight = card.right - 10f * u
            val playBox = MenuBox(playRight - play, card.centerY - play / 2f, playRight, card.centerY + play / 2f)
            val text = MenuBox(artBox.right + 16f * u, card.top, playBox.left - 16f * u, card.bottom)
            return StripBoxes(card, artBox, text, playBox)
        }

        /** The design size the Classic and Compact homes scale from (1280 x 720 less the host rail). */
        private const val DESIGN_WIDTH = 1184f
        private const val DESIGN_HEIGHT = 720f
        /** Shorter than this (dp) and Classic and Compact take the compact header. */
        private const val ROOMY_MIN_HEIGHT = 560f

        /**
         * The card's insides: art and text at the top; the progress bar, its times and the three
         * controls held together at the bottom, so a taller card opens up between the two groups
         * rather than pushing the controls away from the progress they belong to.
         */
        private fun heroBoxes(card: MenuBox, u: Float, profile: HomeDashboardTheme.Profile, look: HomeDashboardLook): HeroBoxes {
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
            val times = MenuBox(card.left + pad, timesBottom - look.text(Dp.HERO_TIME) * LINE_RATIO * u, card.right - pad, timesBottom)
            val barBottom = times.top - Dp.HERO_PROGRESS * u
            val progress = MenuBox(times.left, barBottom - Dp.HERO_PROGRESS * u, times.right, barBottom)
            return HeroBoxes(card, art, text, progress, times, previous, play, next)
        }
    }
}
