package dev.autobridge.browser

/**
 * Everything the menu sheet can do. Each entry maps to logic that already exists elsewhere in the
 * app (a [CarWebRenderer] call, a store, or a Car App screen); the sheet is presentation only and
 * implements no browser behaviour of its own.
 */
enum class DrawerAction {
    NEW_TAB, TABS, BOOKMARKS, HISTORY, DOWNLOADS,
    MEDIA_CENTER, NOW_PLAYING, MEDIA_LIBRARY,
    COPY_URL, PASTE_AND_GO, FIND_IN_PAGE, AGENT,
    TOGGLE_DESKTOP, ZOOM_IN, ZOOM_OUT, RELOAD, HOME,

    /** Car only: immersive fullscreen (no toolbar, floating button fades until touched). */
    TOGGLE_FULLSCREEN,
    BOOKMARK_PAGE, OPEN_EXTERNAL, SETTINGS, CLEAR_DATA, DIAGNOSTICS,

    /** Page history, the two controls the address row sits between. */
    NAV_BACK, NAV_FORWARD,

    /** Opens the address editor. The address row itself and its trailing ⌕ both resolve to this. */
    ADDRESS_KEYBOARD,

    /** Opens the address editor with nothing in it, the row's leading-to-trailing "start over". */
    ADDRESS_CLEAR,

    /**
     * Leaves the browser entirely and returns to AutoBridge's main dashboard — the car surface's
     * only route back to Home, since [CarBrowserScreen][dev.autobridge.car.CarBrowserScreen]
     * deliberately carries no header Back action of its own (see the comment on its
     * `onGetTemplate()`). Distinct from [HOME], which stays inside the browser and loads the
     * configured start page.
     */
    APP_HOME,

    /** Dismisses the sheet and changes nothing else. */
    CLOSE_SHEET,

    /** Opens the secondary "More" list; see [BrowserDrawerModel.moreItems]. */
    MORE,

    /** Returns from the "More" list to the primary sheet. */
    BACK_TO_MENU,

    /** Phone only: hands the page on screen to the browser running on the car surface. */
    SEND_TO_CAR,

    /** Phone only: pulls whatever the car surface is showing back onto the phone. */
    RECEIVE_FROM_CAR,
}

/**
 * Which browser the sheet is drawn for.
 *
 * The two surfaces run the same sheet, but not the same browser: the car surface has tabs, a media
 * centre and an agent screen, and the phone has none of those nor any use for them — it has the
 * links to and from the car instead. Listing an action the surface cannot perform would put a
 * live-looking tile over nothing, so the difference is declared here and the item lists are derived
 * from it, rather than each surface keeping its own copy of a menu that is otherwise identical.
 */
enum class MenuSurface { CAR, PHONE }

/** How a laid-out element is drawn and, on the phone, which widget it becomes. */
enum class DrawerKind {
    /** A large square-ish button with a glyph over a label. */
    TILE,

    /** A full-width row carrying a label and a switch. */
    TOGGLE,

    /** A small labelled pill in the footer. */
    PILL,

    /** A circular button inside the address row. */
    ROUND,

    /** The address row itself: tapping anywhere that is not one of its buttons edits the URL. */
    ADDRESS,
}

/** A tappable entry, before it has been given a place on screen. */
data class DrawerItem(
    val action: DrawerAction,
    val label: String,
    val glyph: String,
    /** Corner state text, e.g. a tab count. Blank when the entry has no state. */
    val value: String = "",
    /**
     * False for an entry that is visible but cannot act right now — Back with no history behind it.
     * Drawn dimmed and ignored by [BrowserDrawerModel.actionAt], so it never looks pressable and
     * does nothing, which is the failure mode of drawing a live-looking control over dead logic.
     */
    val enabled: Boolean = true,
    /** Switch state, for [DrawerKind.TOGGLE] only. */
    val on: Boolean = false,
)

/** A laid-out entry with the box it occupies in surface coordinates. */
data class DrawerRow(val item: DrawerItem, val bounds: Box, val kind: DrawerKind)

/** A group of tiles drawn on a shared card, the way the reference menu groups related actions. */
data class DrawerCard(val bounds: Box, val tiles: List<DrawerRow>)

/** The address row: a pill carrying the page's identity and two controls. */
data class DrawerAddress(
    val bounds: Box,
    val text: String,
    val secure: Boolean,
    val clear: Box,
    val go: Box,
)

/** Live browser state the sheet reports. Nothing here is computed by the sheet itself. */
data class BrowserMenuState(
    val appName: String = "AutoBridge",
    val pageTitle: String = "",
    val url: String = "",
    val tabCount: Int = 1,
    val isDesktop: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val version: String = "",
    val surface: MenuSurface = MenuSurface.CAR,
) {
    val secure: Boolean get() = url.startsWith("https://", ignoreCase = true)
}

/**
 * Content and geometry for the browser menu sheet.
 *
 * The sheet is an **overlay**: it is composited over the page and never reduces the viewport the
 * WebView is laid out against. A panel that pushes content would change the page's width on every
 * open and close, reflowing the site — the exact class of movement this work removes. That
 * trade-off is taken deliberately on every screen size, including wide ones.
 *
 * **Shape.** Header, address row, cards of tiles, a desktop-site switch and a footer, in that
 * order — one column, top to bottom. It replaces a flat grid of undifferentiated tiles, where
 * "Bookmarks" and "Zoom out" were drawn identically and the page's own address was nowhere on the
 * sheet at all. Grouping is what makes a menu scannable at a glance, which is the only kind of
 * reading that happens in a car.
 *
 * Three rules make the same content work on every head unit:
 *
 * 1. **The sheet starts below the toolbar, never under it.** It used to begin one margin below the
 *    top of the viewport, inside the band the toolbar occupies. Since [CarWebRenderer] drew the
 *    toolbar *after* the sheet, the bar painted over the sheet's header: the close button ended up
 *    73% hidden behind the fullscreen icon, and the header title was covered outright. Taps were
 *    worse than the drawing — the top ~16dp of the bar fell outside the panel and dismissed the
 *    sheet, while the rest of it fell *inside* the panel and was swallowed, so visibly-lit buttons
 *    did nothing. Starting below the bar makes the header always visible and gives every tap
 *    outside the panel one meaning.
 *
 * 2. **The sheet is bounded in width and centred.** [AutoUiSizes.MENU_SHEET_MAX_WIDTH_DP] keeps a
 *    1920px-wide head unit from stretching three tiles across the whole panel; the reference
 *    proportions are a phone's, and they are what the layout keeps.
 *
 * 3. **Bands are sized from the box, not authored and hoped for.** Every band declares a preferred
 *    and a minimum height and [distribute] shrinks them together toward those minimums, so the
 *    sheet only scrolls once even the minimums do not fit — which, with the reference layout, is
 *    true of a stable area around 320px tall and nothing larger. The header and its close button
 *    never scroll, so the way out is in the same place whatever the content is doing.
 *
 * Geometry is pure data so it can be asserted at each supported head unit resolution in a JVM test.
 */
class BrowserDrawerModel private constructor(
    val sizes: AutoUiSizes,
    val panel: Box,
    /** Caption naming which list is open, e.g. "AutoBridge" or "More". */
    val title: String,
    val subtitle: String,
    /** The decorative drag pill at the top of the sheet. Not a tap target. */
    val grip: Box,
    val headerCard: Box,
    /**
     * The sheet's own close target, in the header. The sheet covers most of the viewport, so "tap
     * outside to close" asked the user to hit a band a few dp wide while the car moves — in
     * practice the sheet could not be dismissed except by picking an action. A button in a fixed
     * place is the way out.
     */
    val closeButton: Box,
    val address: DrawerAddress,
    val cards: List<DrawerCard>,
    val toggle: DrawerRow?,
    val footer: Box,
    val footerName: String,
    val footerVersion: String,
    val footerLinks: List<DrawerRow>,
    /** Everything below this scrolls; the header above it does not. */
    val headerBottom: Float,
    val contentHeight: Float,
    val visibleHeight: Float,
    val scrollOffset: Float,
) {
    companion object {
        /** The nine actions of the primary sheet, in the two cards they are drawn on. */
        fun primaryCards(state: BrowserMenuState): List<List<DrawerItem>> = listOf(
            // History and reload first, because they are what a menu opened mid-page is usually
            // opened for, and because the toolbar they duplicate auto-hides — this is the copy
            // that is always on screen once the sheet is open.
            listOf(
                DrawerItem(DrawerAction.NAV_BACK, "Back", "←", enabled = state.canGoBack),
                DrawerItem(DrawerAction.RELOAD, "Reload", "↻"),
                DrawerItem(DrawerAction.NAV_FORWARD, "Forward", "→", enabled = state.canGoForward),
                DrawerItem(DrawerAction.BOOKMARKS, "Bookmarks", "☆"),
                DrawerItem(DrawerAction.OPEN_EXTERNAL, "External", "↗"),
                DrawerItem(DrawerAction.SETTINGS, "Settings", "⚙"),
            ),
            // The car browser is tabbed and the phone one is not, so the phone spends the same
            // three slots on the two lists it does have. Same shape, same place, no dead tile.
            when (state.surface) {
                MenuSurface.CAR -> listOf(
                    DrawerItem(DrawerAction.HOME, "Start Page", "⌂"),
                    DrawerItem(DrawerAction.TABS, "Tabs", "▣", state.tabCount.toString()),
                    DrawerItem(DrawerAction.NEW_TAB, "New Tab", "＋"),
                )
                MenuSurface.PHONE -> listOf(
                    DrawerItem(DrawerAction.HOME, "Start Page", "⌂"),
                    DrawerItem(DrawerAction.HISTORY, "History", "↺"),
                    DrawerItem(DrawerAction.DOWNLOADS, "Downloads", "↓"),
                )
            },
        )

        /** The one switch on the primary sheet; a switch because the state is what it reports. */
        fun desktopToggle(state: BrowserMenuState): DrawerItem = DrawerItem(
            DrawerAction.TOGGLE_DESKTOP, "Request desktop site", "▭", on = state.isDesktop
        )

        /**
         * Everything that did not earn a tile on the primary sheet, reached through the footer's
         * "More" link. One adaptive grid rather than headed sections: a drawn heading cost a whole
         * band for a word that self-labelled tiles already imply, and on a short panel that band
         * was the difference between the last row fitting and falling off the bottom.
         */
        fun moreItems(surface: MenuSurface = MenuSurface.CAR): List<DrawerItem> = when (surface) {
            MenuSurface.CAR -> carMoreItems()
            MenuSurface.PHONE -> phoneMoreItems()
        }

        private fun carMoreItems(): List<DrawerItem> = listOf(
            // First in the list: with the URL bar hidden by default, this is the only route to
            // fullscreen that does not need the floating button rebound.
            DrawerItem(DrawerAction.TOGGLE_FULLSCREEN, "Fullscreen", "⛶"),
            DrawerItem(DrawerAction.HISTORY, "History", "↺"),
            DrawerItem(DrawerAction.DOWNLOADS, "Downloads", "↓"),
            DrawerItem(DrawerAction.BOOKMARK_PAGE, "Bookmark", "★"),
            DrawerItem(DrawerAction.FIND_IN_PAGE, "Find", "⌕"),
            DrawerItem(DrawerAction.COPY_URL, "Copy URL", "⧉"),
            // Always offered. Probing the clipboard to decide whether to show this entry does not
            // work on a car surface: Android denies clipboard reads to an app that is not focused
            // on the phone, so the probe always failed, the entry never appeared, and every sheet
            // open logged a denial. Whether there is anything to paste is decided when it is
            // tapped.
            DrawerItem(DrawerAction.PASTE_AND_GO, "Paste & go", "⎘"),
            DrawerItem(DrawerAction.ZOOM_IN, "Zoom in", "+"),
            DrawerItem(DrawerAction.ZOOM_OUT, "Zoom out", "−"),
            DrawerItem(DrawerAction.AGENT, "Agent", "❖"),
            DrawerItem(DrawerAction.MEDIA_CENTER, "Media", "♪"),
            DrawerItem(DrawerAction.NOW_PLAYING, "Now playing", "▶"),
            DrawerItem(DrawerAction.MEDIA_LIBRARY, "Library", "▤"),
            DrawerItem(DrawerAction.CLEAR_DATA, "Clear data", "⌧"),
            DrawerItem(DrawerAction.DIAGNOSTICS, "About", "ℹ"),
        )

        /**
         * The phone's secondary list. History and Downloads are absent because the phone keeps them
         * on its primary sheet; the car's media, agent and diagnostics entries are absent because
         * they are Car App screens with no phone equivalent. What the phone has instead is the pair
         * of links to the car surface, which is most of the reason this browser exists on the phone.
         */
        private fun phoneMoreItems(): List<DrawerItem> = listOf(
            DrawerItem(DrawerAction.BOOKMARK_PAGE, "Bookmark", "★"),
            DrawerItem(DrawerAction.FIND_IN_PAGE, "Find", "⌕"),
            DrawerItem(DrawerAction.COPY_URL, "Copy URL", "⧉"),
            DrawerItem(DrawerAction.PASTE_AND_GO, "Paste & go", "⎘"),
            DrawerItem(DrawerAction.ZOOM_IN, "Zoom in", "+"),
            DrawerItem(DrawerAction.ZOOM_OUT, "Zoom out", "−"),
            DrawerItem(DrawerAction.SEND_TO_CAR, "Send to car", "▶"),
            DrawerItem(DrawerAction.RECEIVE_FROM_CAR, "Get from car", "◀"),
            DrawerItem(DrawerAction.CLEAR_DATA, "Clear data", "⌧"),
        )

        /** Fixed at three so the sheet keeps the reference layout's proportions on every panel. */
        const val PRIMARY_COLUMNS = 3

        fun create(
            sizes: AutoUiSizes,
            viewport: BrowserViewport,
            state: BrowserMenuState,
            more: Boolean = false,
            scrollOffset: Float = 0f,
        ): BrowserDrawerModel {
            val gap = sizes.contentGap
            val pad = sizes.horizontalPadding

            // Below the toolbar, never under it. See the class doc, rule 1.
            val top = viewport.top + sizes.toolbarHeight(viewport.height)
            val bottom = (viewport.top + viewport.height - gap).coerceAtLeast(top + 1f)
            val available = (viewport.width - gap * 2f).coerceAtLeast(1f)
            val width = available.coerceAtMost(sizes.menuSheetMaxWidth)
            val left = viewport.left + (viewport.width - width) / 2f
            val panel = Box(left, top, left + width, bottom)

            val gripWidth = (panel.width * 0.16f).coerceAtLeast(sizes.touchTarget)
            val gripHeight = sizes.dp(4f)
            val grip = Box(
                panel.centerX - gripWidth / 2f, panel.top + gap,
                panel.centerX + gripWidth / 2f, panel.top + gap + gripHeight
            )

            // The header gives way to the content on a short panel instead of holding a fixed share
            // of a height there is none of. Floored so it can still carry a hittable close button.
            val headerHeight = (panel.height * 0.15f)
                .coerceIn(sizes.dp(AutoUiSizes.MIN_HEADER_HEIGHT_DP), sizes.touchTarget * 1.5f)
            val headerCard = Box(panel.left + gap, grip.bottom + gap, panel.right - gap, grip.bottom + gap + headerHeight)
            val headerBottom = headerCard.bottom

            val closeHeight = (headerHeight - gap * 0.5f).coerceIn(sizes.touchTarget * 0.75f, sizes.touchTarget)
            // Wide enough for the glyph and the word beside it: "✕" alone in a car is a guess, and
            // this is the control the user needs when they opened the sheet by mistake.
            val closeWidth = (sizes.touchTarget * 2.2f).coerceAtMost(headerCard.width * 0.45f)
            val closeButton = Box(
                headerCard.right - pad - closeWidth, headerCard.centerY - closeHeight / 2f,
                headerCard.right - pad, headerCard.centerY + closeHeight / 2f
            )

            val innerLeft = panel.left + gap
            val innerRight = panel.right - gap
            val innerWidth = (innerRight - innerLeft).coerceAtLeast(1f)
            val contentTop = headerBottom + gap
            val contentBottom = panel.bottom - gap
            val visibleHeight = (contentBottom - contentTop).coerceAtLeast(1f)

            val cardPad = gap * 0.75f
            val tileGap = sizes.menuTileGap
            val itemGroups = if (more) listOf(moreItems(state.surface)) else primaryCards(state)
            val columns = itemGroups.map { group ->
                if (more) {
                    sizes.menuColumns(innerWidth - cardPad * 2f).coerceAtLeast(PRIMARY_COLUMNS)
                } else {
                    PRIMARY_COLUMNS
                }
            }
            val tileRows = itemGroups.mapIndexed { index, group ->
                (group.size + columns[index] - 1) / columns[index]
            }

            // Fixed chrome: the gaps between bands and each card's own padding and inter-tile gaps.
            // Whatever is left is what the bands below get to share.
            val bandCount = 2 + itemGroups.size + (if (more) 0 else 1) // address + cards + toggle? + footer
            val chrome = gap * (bandCount - 1) +
                itemGroups.indices.sumOf { (cardPad * 2f + tileGap * (tileRows[it] - 1)).toDouble() }.toFloat()

            // Each band declares what it wants and the least it will accept; see the class doc,
            // rule 3. Tile rows are listed individually so they shrink in step with each other.
            val bands = ArrayList<Pair<Float, Float>>()
            bands += sizes.touchTarget to sizes.touchTarget * 0.8f // address
            tileRows.forEach { rows ->
                repeat(rows) {
                    bands += sizes.dp(AutoUiSizes.MENU_TILE_HEIGHT_DP) to sizes.dp(AutoUiSizes.MIN_TILE_HEIGHT_DP)
                }
            }
            if (!more) bands += sizes.touchTarget to sizes.touchTarget * 0.8f // desktop switch
            bands += sizes.touchTarget * 0.8f to sizes.dp(AutoUiSizes.MIN_FOOTER_HEIGHT_DP)
            val heights = distribute(bands, (visibleHeight - chrome).coerceAtLeast(0f))

            val contentHeight = heights.sum() + chrome
            val maxScroll = (contentHeight - visibleHeight).coerceAtLeast(0f)
            val offset = scrollOffset.coerceIn(0f, maxScroll)

            // Top-aligned, not centred: the sheet reads as a stack of sections from the header
            // down, and centring a short stack would float it away from the header it belongs to.
            var cursor = contentTop - offset
            var band = 0
            fun take(): Float = heights[band++]

            val addressHeight = take()
            val addressBox = Box(innerLeft, cursor, innerRight, cursor + addressHeight)
            val roundSide = (addressHeight - gap * 0.5f).coerceAtMost(sizes.touchTarget)
            val goBox = Box(
                addressBox.right - gap * 0.5f - roundSide, addressBox.centerY - roundSide / 2f,
                addressBox.right - gap * 0.5f, addressBox.centerY + roundSide / 2f
            )
            val clearBox = Box(
                goBox.left - gap * 0.5f - roundSide, addressBox.centerY - roundSide / 2f,
                goBox.left - gap * 0.5f, addressBox.centerY + roundSide / 2f
            )
            val address = DrawerAddress(
                addressBox, BrowserDisplayUrl.compact(state.url), state.secure, clearBox, goBox
            )
            cursor = addressBox.bottom + gap

            val cards = ArrayList<DrawerCard>(itemGroups.size)
            itemGroups.forEachIndexed { index, group ->
                val rows = tileRows[index]
                val tileHeights = (0 until rows).map { take() }
                val cardHeight = tileHeights.sum() + cardPad * 2f + tileGap * (rows - 1)
                val cardBox = Box(innerLeft, cursor, innerRight, cursor + cardHeight)
                val cols = columns[index]
                val tileWidth = (cardBox.width - cardPad * 2f - tileGap * (cols - 1)) / cols
                val tiles = ArrayList<DrawerRow>(group.size)
                group.forEachIndexed { position, item ->
                    val column = position % cols
                    val row = position / cols
                    val tileTop = cardBox.top + cardPad +
                        tileHeights.take(row).sum() + tileGap * row
                    val tileLeft = cardBox.left + cardPad + column * (tileWidth + tileGap)
                    tiles += DrawerRow(
                        item,
                        Box(tileLeft, tileTop, tileLeft + tileWidth, tileTop + tileHeights[row]),
                        DrawerKind.TILE
                    )
                }
                cards += DrawerCard(cardBox, tiles)
                cursor = cardBox.bottom + gap
            }

            val toggle = if (more) null else {
                val height = take()
                val box = Box(innerLeft, cursor, innerRight, cursor + height)
                cursor = box.bottom + gap
                DrawerRow(desktopToggle(state), box, DrawerKind.TOGGLE)
            }

            val footerHeight = take()
            val footer = Box(innerLeft, cursor, innerRight, cursor + footerHeight)
            // Two pills, matching the reference footer's link-plus-icon pair. "More"/"Back" moves
            // between the two lists; "Exit" is the car surface's only route out of the browser and
            // therefore never lives behind another tap.
            val pillHeight = (footerHeight - gap * 0.4f).coerceAtLeast(sizes.touchTarget * 0.6f)
            val pillWidth = (sizes.touchTarget * 1.7f).coerceAtMost(footer.width * 0.3f)
            val exitBox = Box(
                footer.right - pillWidth, footer.centerY - pillHeight / 2f,
                footer.right, footer.centerY + pillHeight / 2f
            )
            val moreBox = Box(
                exitBox.left - gap * 0.75f - pillWidth, footer.centerY - pillHeight / 2f,
                exitBox.left - gap * 0.75f, footer.centerY + pillHeight / 2f
            )
            val footerLinks = listOf(
                DrawerRow(
                    if (more) DrawerItem(DrawerAction.BACK_TO_MENU, "Back", "‹")
                    else DrawerItem(DrawerAction.MORE, "More", "⋯"),
                    moreBox, DrawerKind.PILL
                ),
                DrawerRow(
                    DrawerItem(DrawerAction.APP_HOME, "Exit", "⏏"), exitBox, DrawerKind.PILL
                ),
            )

            return BrowserDrawerModel(
                sizes = sizes,
                panel = panel,
                title = if (more) "More" else state.appName,
                subtitle = if (more) "All browser actions" else state.pageTitle,
                grip = grip,
                headerCard = headerCard,
                closeButton = closeButton,
                address = address,
                cards = cards,
                toggle = toggle,
                footer = footer,
                footerName = "${state.appName} — Browser",
                footerVersion = state.version,
                footerLinks = footerLinks,
                headerBottom = headerBottom,
                contentHeight = contentHeight,
                visibleHeight = visibleHeight,
                scrollOffset = offset,
            )
        }

        /**
         * Shares [available] between bands that each declare a `(preferred, minimum)` height.
         *
         * Everything gets its preferred height when they all fit. When they do not, each band gives
         * up the same *fraction* of its own slack, so a short panel compresses evenly instead of
         * letting whichever band happens to be laid out first take the whole space. Once every band
         * is at its minimum nothing shrinks further and the caller scrolls.
         */
        internal fun distribute(bands: List<Pair<Float, Float>>, available: Float): List<Float> {
            val preferred = bands.sumOf { it.first.toDouble() }.toFloat()
            if (bands.isEmpty() || preferred <= available) return bands.map { it.first }
            val slack = bands.sumOf { (it.first - it.second).toDouble() }.toFloat()
            if (slack <= 0f) return bands.map { it.second }
            val factor = ((preferred - available) / slack).coerceIn(0f, 1f)
            return bands.map { (want, floor) -> want - (want - floor) * factor }
        }
    }

    val maxScroll: Float get() = (contentHeight - visibleHeight).coerceAtLeast(0f)

    /** Every tile on the sheet, across all cards. */
    val tiles: List<DrawerRow> get() = cards.flatMap { it.tiles }

    /** Everything that can be tapped below the header, in the order hit testing resolves them. */
    val rows: List<DrawerRow>
        get() = buildList {
            add(DrawerRow(DrawerItem(DrawerAction.ADDRESS_CLEAR, "Clear", "✕"), address.clear, DrawerKind.ROUND))
            add(DrawerRow(DrawerItem(DrawerAction.ADDRESS_KEYBOARD, "Search", "⌕"), address.go, DrawerKind.ROUND))
            add(DrawerRow(DrawerItem(DrawerAction.ADDRESS_KEYBOARD, "Address", "🔒"), address.bounds, DrawerKind.ADDRESS))
            addAll(tiles)
            toggle?.let { add(it) }
            addAll(footerLinks)
        }

    /** True when a tap landed on the header's close button. Checked before [actionAt]. */
    fun hitsClose(x: Float, y: Float): Boolean = closeButton.contains(x, y)

    /**
     * The entry under a tap, or null when the tap fell on the sheet's background.
     *
     * A disabled entry resolves to null rather than to its action: Back with no history behind it
     * is drawn so the row keeps its shape, but it must not swallow the tap and do nothing, which
     * reads as a broken button rather than an unavailable one.
     */
    fun rowAt(x: Float, y: Float): DrawerRow? {
        if (!panel.contains(x, y)) return null
        // Entries scrolled under the header must not be tappable even though their box overlaps it.
        if (y < headerBottom) return null
        return rows.firstOrNull { it.item.enabled && it.bounds.contains(x, y) }
    }

    /** Convenience for call sites that only need to know what a tap means. */
    fun actionAt(x: Float, y: Float): DrawerAction? =
        if (hitsClose(x, y)) DrawerAction.CLOSE_SHEET else rowAt(x, y)?.item?.action
}
