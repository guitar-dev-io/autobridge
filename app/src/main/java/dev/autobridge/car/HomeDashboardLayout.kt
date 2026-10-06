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
    CONTINUE, QUICK_ACCESS, RECENT_HEADER, RECENT_ITEM, QUEUE_HEADER, QUEUE_ITEM, SCROLL_UP, SCROLL_DOWN
}

/** A resolved tap: which region, and which row of it when the region is a list. */
internal data class HomeHit(val region: HomeRegion, val index: Int = -1)

/**
 * Responsive geometry for the whole home dashboard, computed from the area the host leaves free.
 *
 * The screen is one vertical column — header, Continue Watching, Quick Access, then Recently Sent
 * and Queue — and its heights come from [HomeDashboardTheme.Budget]: a share of the free height per
 * section, clamped into a dp range. That ordering matters. Sizing each piece by what it measures
 * and giving the remainder to the cards, which is the obvious way round, lets a long title or an
 * extra queue row quietly eat the thing the driver actually aims at; a budget cannot, because the
 * grid's ~46% is taken out first and nothing above it can spend it.
 *
 * A section with no content drops out and its share is scaled across the rest, so a head unit with
 * no queue gets bigger cards rather than a hole. The Recently Sent / Queue blocks are budgeted for
 * their *header* only: their rows are laid out in full below it and reached by scrolling, which is
 * what keeps a list that happens to be long from pushing the grid off the screen.
 *
 * When the dp floors no longer fit the free height — the smallest head units — the column scrolls
 * as a whole with the [scrollUp] / [scrollDown] buttons, exactly as the six-card menu did before
 * it, rather than honouring the percentages down to untouchable cards.
 *
 * Widths follow the card grid: the cards are clamped so an ultra-wide head unit does not get
 * 600dp-wide tiles, and every other section is aligned to the grid's edges so the column reads as
 * one component. Recently Sent and Queue sit side by side when that column is wide enough for two
 * readable blocks, and stack when it is not.
 */
internal class HomeDashboardLayout private constructor(
    val density: Float,
    val logo: MenuBox,
    /** Title start; it is centred on [logo] vertically by the renderer, which owns the font metrics. */
    val titleX: Float,
    val titleSize: Float,
    /** Clip region of the scrolling column; equal to the content bounds unless scrolling is needed. */
    val viewport: MenuBox,
    val continueTitle: MenuBox?,
    val continueCard: MenuBox?,
    val quickTitle: MenuBox,
    /** Card bounds at scroll offset 0, row-major. */
    val cards: List<MenuBox>,
    val recentBlock: MenuBox?,
    val recentHeader: MenuBox?,
    val recentRows: List<MenuBox>,
    val queueBlock: MenuBox?,
    val queueHeader: MenuBox?,
    val queueRows: List<MenuBox>,
    val cornerRadius: Float,
    val blockRadius: Float,
    val rowRadius: Float,
    val thumbRadius: Float,
    val iconSize: Float,
    val iconLabelGap: Float,
    val labelSize: Float,
    val labelMaxWidth: Float,
    val sectionTitleSize: Float,
    val blockHeaderSize: Float,
    val heroTitleSize: Float,
    val heroMetaSize: Float,
    val rowTitleSize: Float,
    val rowMetaSize: Float,
    val maxScroll: Float,
    val scrollUp: MenuBox?,
    val scrollDown: MenuBox?
) {
    val scrollable: Boolean get() = maxScroll > 0f

    fun dp(value: Float) = value * density

    /**
     * What a tap at ([x], [y]) hits with the column at [scroll], or null for empty space.
     *
     * The order is the one the dashboard spec fixes: Continue Watching, the quick-access cards,
     * then each block's header before its rows. Regions never overlap, so the order only decides
     * which of two adjacent edges wins a tap exactly between them.
     */
    fun hit(x: Float, y: Float, scroll: Float): HomeHit? {
        // The scroll buttons sit in their own column outside the viewport and never move with it.
        if (scrollUp?.contains(x, y) == true) return HomeHit(HomeRegion.SCROLL_UP)
        if (scrollDown?.contains(x, y) == true) return HomeHit(HomeRegion.SCROLL_DOWN)
        if (!viewport.contains(x, y)) return null

        continueCard?.offset(-scroll)?.let { if (it.contains(x, y)) return HomeHit(HomeRegion.CONTINUE) }
        cards.forEachIndexed { index, box ->
            if (box.offset(-scroll).contains(x, y)) return HomeHit(HomeRegion.QUICK_ACCESS, index)
        }
        recentHeader?.offset(-scroll)?.let { if (it.contains(x, y)) return HomeHit(HomeRegion.RECENT_HEADER) }
        recentRows.forEachIndexed { index, box ->
            if (box.offset(-scroll).contains(x, y)) return HomeHit(HomeRegion.RECENT_ITEM, index)
        }
        queueHeader?.offset(-scroll)?.let { if (it.contains(x, y)) return HomeHit(HomeRegion.QUEUE_HEADER) }
        queueRows.forEachIndexed { index, box ->
            if (box.offset(-scroll).contains(x, y)) return HomeHit(HomeRegion.QUEUE_ITEM, index)
        }
        return null
    }

    /** One card row plus its gap: what a single scroll-button tap moves. */
    val scrollStep: Float
        get() = if (cards.size > COLUMNS) cards[COLUMNS].top - cards[0].top else viewport.height / 2f

    companion object {
        const val COLUMNS = 3

        /** Where one section sits and how tall its budget band is. */
        private data class Band(val top: Float, val height: Float) {
            val bottom get() = top + height
        }

        /**
         * @param safe area not covered by host chrome (visible/stable area), in surface pixels.
         * @param measure width of a label at a text size in pixels, from the real paint.
         */
        fun compute(
            safe: MenuBox,
            density: Float,
            labels: List<String>,
            content: HomeDashboardContent,
            measure: (String, Float) -> Float
        ): HomeDashboardLayout {
            val d = density
            val budget = HomeDashboardTheme.Budget
            val top = safe.top + Dp.TOP_PADDING * d
            val bottom = safe.bottom - Dp.BOTTOM_PADDING * d
            val usable = max(1f, bottom - top)
            val margin = (safe.width * 0.035f).coerceIn(Dp.MARGIN_MIN * d, Dp.MARGIN_MAX * d)
            val gap = (usable * 0.045f).coerceIn(Dp.GAP_COMPACT * d, Dp.GAP * d)
            val buttonSize = Dp.SCROLL_BUTTON * d

            val hasHero = content.hasContinueWatching
            val hasBlocks = content.hasRecentlySent || content.hasQueue

            // --- the budget -----------------------------------------------------------------
            // Absent sections hand their share back; the rest are scaled to fill the same total,
            // so the proportions between what *is* on screen stay the ones the design fixes.
            val claimed = budget.HEADER + budget.QUICK_ACCESS_TITLE + budget.QUICK_ACCESS_GRID +
                (if (hasHero) budget.CONTINUE_WATCHING else 0f) +
                (if (hasBlocks) budget.BLOCK_HEADER else 0f)
            val scale = budget.SPENDABLE / claimed

            fun band(share: Float, minDp: Float, maxDp: Float) =
                (usable * share * scale).coerceIn(minDp * d, maxDp * d)

            val headerBand = band(budget.HEADER, Dp.HEADER_BAND_MIN, Dp.HEADER_BAND_MAX)
            val heroBand =
                if (hasHero) band(budget.CONTINUE_WATCHING, Dp.HERO_BAND_MIN, Dp.HERO_BAND_MAX) else 0f
            val quickTitleBand =
                band(budget.QUICK_ACCESS_TITLE, Dp.QUICK_TITLE_BAND_MIN, Dp.QUICK_TITLE_BAND_MAX)
            val gridBand = band(budget.QUICK_ACCESS_GRID, Dp.GRID_BAND_MIN, Dp.GRID_BAND_MAX)
            val peekBand = if (hasBlocks) band(budget.BLOCK_HEADER, Dp.BLOCK_PEEK_MIN, Dp.BLOCK_PEEK_MAX) else 0f

            // --- what each band spends its height on ----------------------------------------
            val sectionTitleGap = Dp.SECTION_TITLE_GAP * d
            val sectionTitleSize = ((quickTitleBand - sectionTitleGap) / LINE_RATIO)
                .coerceIn(Dp.SECTION_TITLE_MIN * d, Dp.SECTION_TITLE * d)
            val sectionTitleLine = sectionTitleSize * LINE_RATIO

            val logoSize = (headerBand - gap).coerceIn(Dp.LOGO_COMPACT * d, Dp.LOGO_MAX * d)
            val wordmarkSize = logoSize * HomeDashboardTheme.WORDMARK_RATIO

            val heroHeight = (heroBand - sectionTitleLine - sectionTitleGap - gap)
                .coerceIn(Dp.HERO_MIN_HEIGHT * d, Dp.HERO_MAX_HEIGHT * d)

            val blockPadding = Dp.BLOCK_PADDING * d
            val blockHeaderSize = ((peekBand - blockPadding) / LINE_RATIO)
                .coerceIn(Dp.BLOCK_HEADER_MIN * d, Dp.BLOCK_HEADER * d)
            val blockHeaderLine = blockHeaderSize * LINE_RATIO
            // Rows are not budgeted: they fill what the bands left and then run past the fold.
            val rowHeight = (usable * 0.105f).coerceIn(Dp.ROW_MIN_HEIGHT * d, Dp.ROW_MAX_HEIGHT * d)

            val gridRows = (labels.size + COLUMNS - 1) / COLUMNS
            val bandCardHeight = ((gridBand - gap * gridRows) / gridRows).coerceAtLeast(Dp.CARD_MIN_HEIGHT * d)

            // Side by side is decided on the column's real width, which is only known once the
            // cards are sized; the area width is an upper bound on it and settles the common case
            // in one go, with a single correction below for the rest.
            var sideBySide = (safe.width - margin * 2) >= HomeDashboardTheme.SIDE_BY_SIDE_MIN_WIDTH * d

            fun blockHeight(rows: Int): Float =
                if (rows <= 0) 0f
                else blockPadding * 2 + blockHeaderLine + Dp.BLOCK_HEADER_GAP * d +
                    rows * rowHeight + (rows - 1) * Dp.ROW_GAP * d

            /** One full pass at the geometry; [scrolling] is what reserves the button column. */
            fun place(scrolling: Boolean): HomeDashboardLayout {
                val areaLeft = safe.left + margin + if (scrolling) buttonSize + Dp.SCROLL_COLUMN_GAP * d else 0f
                val areaRight = safe.right - margin
                var cardWidth = (areaRight - areaLeft - gap * (COLUMNS - 1)) / COLUMNS
                cardWidth = min(cardWidth, bandCardHeight * HomeDashboardTheme.CARD_MAX_ASPECT)
                // Very narrow units: never let a card be taller than it is wide-ish.
                val cardHeight = min(bandCardHeight, max(cardWidth * 1.1f, Dp.CARD_MIN_HEIGHT * d))
                val gridWidth = cardWidth * COLUMNS + gap * (COLUMNS - 1)
                val left = areaLeft + (areaRight - areaLeft - gridWidth) / 2f
                val right = left + gridWidth

                // A band is at least its budget and at most what it actually has to hold, so a
                // floored card or a long title overflows into the scroll rather than overlapping.
                fun advance(band: Float, content: Float) = max(band, content + gap)

                val header = Band(top, advance(headerBand, logoSize))
                val headerLeft = if (scrolling) safe.left + margin else left
                val logo = MenuBox(headerLeft, header.top, headerLeft + logoSize, header.top + logoSize)

                var continueTitle: MenuBox? = null
                var continueCard: MenuBox? = null
                var cursor = header.bottom
                if (hasHero) {
                    val hero = Band(cursor, advance(heroBand, sectionTitleLine + sectionTitleGap + heroHeight))
                    continueTitle = MenuBox(left, hero.top, right, hero.top + sectionTitleLine)
                    val cardTop = hero.top + sectionTitleLine + sectionTitleGap
                    continueCard = MenuBox(left, cardTop, right, cardTop + heroHeight)
                    cursor = hero.bottom
                }

                val titleBandHeight = max(quickTitleBand, sectionTitleLine + sectionTitleGap)
                val quickTitle = MenuBox(left, cursor, right, cursor + sectionTitleLine)
                cursor += titleBandHeight

                val gridTop = cursor
                val cards = labels.indices.map { index ->
                    val column = index % COLUMNS
                    val row = index / COLUMNS
                    val cardLeft = left + column * (cardWidth + gap)
                    val cardTop = gridTop + row * (cardHeight + gap)
                    MenuBox(cardLeft, cardTop, cardLeft + cardWidth, cardTop + cardHeight)
                }
                cursor += advance(gridBand, cardHeight * gridRows + gap * (gridRows - 1))

                // --- Recently Sent / Queue, laid out in full below the budgeted peek ---------
                var recentBlock: MenuBox? = null
                var recentHeader: MenuBox? = null
                var recentRows: List<MenuBox> = emptyList()
                var queueBlock: MenuBox? = null
                var queueHeader: MenuBox? = null
                var queueRows: List<MenuBox> = emptyList()

                fun rowsOf(block: MenuBox, count: Int): Pair<MenuBox, List<MenuBox>> {
                    val headerBox = MenuBox(
                        block.left + blockPadding, block.top + blockPadding,
                        block.right - blockPadding, block.top + blockPadding + blockHeaderLine
                    )
                    var rowTop = headerBox.bottom + Dp.BLOCK_HEADER_GAP * d
                    val rows = (0 until count).map {
                        val box = MenuBox(block.left + blockPadding, rowTop, block.right - blockPadding, rowTop + rowHeight)
                        rowTop = box.bottom + Dp.ROW_GAP * d
                        box
                    }
                    return headerBox to rows
                }

                if (hasBlocks) {
                    val recentHeight = blockHeight(content.recentlySent.size)
                    val queueHeight = blockHeight(content.queue.size)
                    if (recentHeight > 0f && queueHeight > 0f && sideBySide) {
                        val half = (gridWidth - gap) / 2f
                        recentBlock = MenuBox(left, cursor, left + half, cursor + recentHeight)
                        queueBlock = MenuBox(right - half, cursor, right, cursor + queueHeight)
                        cursor += max(recentHeight, queueHeight)
                    } else {
                        if (recentHeight > 0f) {
                            recentBlock = MenuBox(left, cursor, right, cursor + recentHeight)
                            cursor += recentHeight
                        }
                        if (recentHeight > 0f && queueHeight > 0f) cursor += gap
                        if (queueHeight > 0f) {
                            queueBlock = MenuBox(left, cursor, right, cursor + queueHeight)
                            cursor += queueHeight
                        }
                    }
                    recentBlock?.let {
                        val (header2, rows) = rowsOf(it, content.recentlySent.size)
                        recentHeader = header2
                        recentRows = rows
                    }
                    queueBlock?.let {
                        val (header2, rows) = rowsOf(it, content.queue.size)
                        queueHeader = header2
                        queueRows = rows
                    }
                }

                val contentBottom = cursor
                // The column overflows when its content runs past the free height, which is what
                // decides whether the scrolling pass is needed. That is always measured against
                // the available bottom; the non-scrolling viewport then wraps the content exactly,
                // and the scrolling one clips to the free height with the buttons in their lane.
                val maxScroll = max(0f, contentBottom - bottom)
                val viewport = MenuBox(left, header.bottom, right, if (scrolling) bottom else contentBottom)

                // --- quick-access card internals ---------------------------------------------
                val cardPadding = Dp.CARD_PADDING * d
                val labelFloor = Dp.LABEL_MIN * d
                val iconLabelGap = (cardHeight * 0.08f).coerceIn(Dp.ICON_LABEL_GAP_MIN * d, Dp.ICON_LABEL_GAP_MAX * d)
                // The icon tile's room still assumes the largest candidate label; the fit loop only
                // ever shrinks the label from here, so this stays a conservative (not undersized) tile.
                val iconRoom = cardHeight - cardPadding * 2 - iconLabelGap - (Dp.LABEL_MAX * d) * 1.2f
                val iconSize = min(min(cardHeight * 0.52f, cardWidth * 0.45f), iconRoom)
                    .coerceIn(Dp.ICON_MIN * d, Dp.ICON_MAX * d)
                // The horizontal card paints the label in the column to the right of the icon tile,
                // not across the whole card, so fit it to that real column (card minus padding, the
                // icon tile and the icon→label gap) or it ellipsizes early on narrow profiles.
                val labelMaxWidth = cardWidth - cardPadding * 2 - iconSize - Dp.CARD_ICON_LABEL_GAP * d
                var labelSize = min(Dp.LABEL_MAX * d, cardHeight * 0.18f).coerceAtLeast(labelFloor)
                // One size for every card, so "TV" and "YouTube Music" read as the same component.
                while (labelSize > labelFloor && labels.any { measure(it, labelSize) > labelMaxWidth }) {
                    labelSize -= 0.5f * d
                }
                labelSize = max(labelSize, labelFloor)

                val scrollUp = if (scrolling) MenuBox(
                    safe.left + margin, viewport.top, safe.left + margin + buttonSize, viewport.top + buttonSize
                ) else null
                val scrollDown = if (scrolling) MenuBox(
                    safe.left + margin, viewport.bottom - buttonSize, safe.left + margin + buttonSize, viewport.bottom
                ) else null

                return HomeDashboardLayout(
                    density = d,
                    logo = logo,
                    titleX = logo.right + Dp.LOGO_TO_TITLE * d,
                    titleSize = wordmarkSize,
                    viewport = viewport,
                    continueTitle = continueTitle,
                    continueCard = continueCard,
                    quickTitle = quickTitle,
                    cards = cards,
                    recentBlock = recentBlock,
                    recentHeader = recentHeader,
                    recentRows = recentRows,
                    queueBlock = queueBlock,
                    queueHeader = queueHeader,
                    queueRows = queueRows,
                    cornerRadius = min(Dp.CARD_RADIUS * d, cardHeight * 0.18f),
                    blockRadius = min(Dp.BLOCK_RADIUS * d, rowHeight * 0.4f),
                    rowRadius = min(Dp.ROW_RADIUS * d, rowHeight * 0.3f),
                    thumbRadius = Dp.THUMB_RADIUS * d,
                    iconSize = iconSize,
                    iconLabelGap = iconLabelGap,
                    labelSize = labelSize,
                    labelMaxWidth = labelMaxWidth,
                    sectionTitleSize = sectionTitleSize,
                    blockHeaderSize = blockHeaderSize,
                    heroTitleSize = (heroHeight * 0.20f).coerceIn(Dp.HERO_TITLE_MIN * d, Dp.HERO_TITLE_MAX * d),
                    heroMetaSize = (heroHeight * 0.145f).coerceIn(Dp.HERO_META_MIN * d, Dp.HERO_META * d),
                    rowTitleSize = (rowHeight * 0.30f).coerceIn(Dp.ROW_TITLE_MIN * d, Dp.ROW_TITLE * d),
                    rowMetaSize = (rowHeight * 0.23f).coerceIn(Dp.ROW_META_MIN * d, Dp.ROW_META * d),
                    maxScroll = maxScroll,
                    scrollUp = scrollUp,
                    scrollDown = scrollDown
                )
            }

            // First pass without the button column; if the column does not fit the free height,
            // the second pass is the one that gets drawn, with the buttons given their own lane.
            var placed = place(scrolling = false)
            if (placed.maxScroll > 0f) placed = place(scrolling = true)
            if (sideBySide && placed.cards.first().width * COLUMNS < HomeDashboardTheme.SIDE_BY_SIDE_MIN_WIDTH * d) {
                sideBySide = false
                placed = place(scrolling = placed.scrollable)
                if (placed.maxScroll > 0f && placed.scrollUp == null) placed = place(scrolling = true)
            }
            return placed
        }

        /** Line box of a text run as a multiple of its size; roughly ascent+descent for this family. */
        private const val LINE_RATIO = 1.3f
    }
}
