package dev.autobridge.browser

import android.content.Context
import androidx.annotation.StringRes
import dev.autobridge.R

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

    /** Immersive fullscreen (no toolbar, floating button/handle fades until touched). */
    TOGGLE_FULLSCREEN,

    /** Car only: steps through [BrowserSplitLayout] (100, 50/50, 40/60, portrait + landscape). */
    SPLIT_LAYOUT,

    /** Car only: puts the main page on the split's side page too, splitting 50/50 if needed. */
    SIDE_SHOW_PAGE,

    /**
     * Car only: hands the destination on the split's map to the car's navigation app; see
     * [MapsHandoff]. The mobile-web map has no turn-by-turn navigation of its own.
     */
    NAVIGATE_MAPS,
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

    /** Opens the Buy Me a Coffee support page in an external browser. */
    SUPPORT,

    /** Opens the generated open-source licenses screen (falls back to the GitHub LICENSE). */
    LICENSES,

    /** Opens the AutoBridge GitHub repository in an external browser. */
    GITHUB,

    /** Phone only: queues the current page — "plays when the current track ends". */
    ADD_TO_QUEUE,

    /** Phone only: flips [BrowserAdBlock], same switch as Settings ▸ Content blocking. */
    TOGGLE_AD_BLOCK,

    /** Car only: keeps the toolbar on screen instead of letting it fade; [BrowserControlsStore]. */
    PIN_TOOLBAR,

    /** Car only: moves the split's side page to the other side; [BrowserSplitStore]. */
    SWAP_SPLIT_SIDES,

    /** Car only: shows the phone's screen (Bridge Mirror) in place of the browser. */
    MIRROR_PHONE,
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

    /**
     * The sheet's one accent-filled, full-width button: icon, bold title, subtitle and chevron.
     * "Send to car" on the phone, "Tabs" on the car — the action each browser is mostly opened for.
     */
    PRIMARY,

    /** A full-width row carrying a label and a switch. */
    TOGGLE,

    /** A small labelled pill in the header. */
    PILL,

    /** A circular button inside the address row or the header. */
    ROUND,

    /** The address row itself: tapping anywhere that is not one of its buttons edits the URL. */
    ADDRESS,
}

/** A tappable entry, before it has been given a place on screen. */
data class DrawerItem(
    val action: DrawerAction,
    /**
     * The entry's name, as a string *id*. [BrowserDrawerModel] is a pure layout model with no
     * context to resolve text against, and its lists are rebuilt from constants on every frame;
     * keeping the id here lets whoever draws the row resolve it in the language in force at that
     * moment. Resolve it with [label].
     */
    @StringRes val labelRes: Int,
    /** The Material Icon drawn for this entry on every surface; see [BrowserIcon]. */
    val icon: BrowserIcon,
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
    /** Second line under the label, for [DrawerKind.PRIMARY] only; 0 for none. */
    @StringRes val detailRes: Int = 0,
) {
    fun label(context: Context): String = context.getString(labelRes)

    /** The second line, or "" when this entry has none. */
    fun detail(context: Context): String =
        if (detailRes == 0) "" else context.getString(detailRes)
}

/** A laid-out entry with the box it occupies in surface coordinates. */
data class DrawerRow(val item: DrawerItem, val bounds: Box, val kind: DrawerKind)

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
    val fullscreen: Boolean = false,
    val adBlockEnabled: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val version: String = "",
    val surface: MenuSurface = MenuSurface.CAR,
    /** Whether the toolbar is pinned on screen ([DrawerAction.PIN_TOOLBAR]). */
    val pinnedToolbar: Boolean = false,
    /** Whether a split is showing, so swapping its sides has something to act on. */
    val splitActive: Boolean = false,
    /**
     * Car actions this particular car browser cannot perform, left out of every list it is shown.
     *
     * The car has three browsers - the Android Auto template one, Bridge Web on the projection
     * route, and the browser running in a Duo Screen pane - and they offer one menu, built here. A
     * browser that genuinely lacks something (a Duo pane has one page, so no tabs or split) names
     * it here rather than keeping a list of its own, so the menus cannot drift apart again.
     */
    val unsupported: Set<DrawerAction> = emptySet(),
) {
    val secure: Boolean get() = url.startsWith("https://", ignoreCase = true)
}

/**
 * Content and geometry for the browser menu sheet drawn on the car surface.
 *
 * The sheet is an **overlay**: it is composited over the page and never reduces the viewport the
 * WebView is laid out against. A panel that pushes content would change the page's width on every
 * open and close, reflowing the site.
 *
 * **Shape.** The same hierarchy as the phone's [BrowserMenuSheet], so the two browsers read as one
 * product: a header (title, page title, round ✕), the address row, one accent-filled primary
 * button, a divider, Back / Reload / Forward, Bookmarks / Settings / More, a divider and the
 * desktop-site switch. The car's primary button is Tabs (the phone's is Send to car); everything
 * rarer lives behind More. The car additionally carries "Exit" as a header pill, because the car
 * browser has no other route back to the AutoBridge dashboard and that route must never be buried.
 *
 * Three rules make the same content work on every head unit:
 *
 * 1. **The sheet starts below the toolbar, never under it**, so the toolbar can never paint over
 *    the header or swallow taps meant for it.
 *
 * 2. **The sheet is bounded in width and centred** ([AutoUiSizes.MENU_SHEET_MAX_WIDTH_DP]).
 *
 * 3. **Bands are sized from the box, not authored and hoped for.** Every band declares a preferred
 *    and a minimum height and [distribute] shrinks them together toward those minimums, so the
 *    sheet only scrolls once even the minimums do not fit. The header never scrolls, so the way out
 *    is in the same place whatever the content is doing.
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
    /** The header band: title on the left, its buttons on the right. Never scrolls. */
    val header: Box,
    /** Where the title block starts, after the back button on the "More" list. */
    val titleLeft: Float,
    /** The round ✕ in the header's trailing corner. */
    val closeButton: Box,
    /** Header buttons other than ✕: Back (on "More") and Exit. Tappable, never scrolled. */
    val headerLinks: List<DrawerRow>,
    /** The address row; the primary sheet only, as on the phone. */
    val address: DrawerAddress?,
    /** The accent-filled primary button; the primary sheet only. */
    val primary: DrawerRow?,
    val tiles: List<DrawerRow>,
    /** Y positions of the hairlines between groups, in surface coordinates. */
    val dividers: List<Float>,
    /** Desktop then Fullscreen, stacked; empty on the "more" list. */
    val toggles: List<DrawerRow>,
    /** Everything below this scrolls; the header above it does not. */
    val headerBottom: Float,
    val contentHeight: Float,
    val visibleHeight: Float,
    val scrollOffset: Float,
) {
    companion object {
        /**
         * The primary sheet's accent button: what this browser is mostly opened for. The car
         * browser is tabbed, so it is the tab switcher (which also opens new tabs); the phone
         * browser exists to hand pages to the car.
         */
        fun primaryAction(state: BrowserMenuState): DrawerItem = when (state.surface) {
            // A car browser without tabs (a Duo Screen pane) leads with the start page instead.
            MenuSurface.CAR -> if (DrawerAction.TABS in state.unsupported) DrawerItem(
                DrawerAction.HOME, R.string.drawer_start_page, BrowserIcon.HOME_PAGE,
            ) else DrawerItem(
                DrawerAction.TABS, R.string.drawer_tabs, BrowserIcon.TABS, value = state.tabCount.toString(),
                detailRes = R.string.browser_tabs_detail,
            )
            MenuSurface.PHONE -> DrawerItem(
                DrawerAction.SEND_TO_CAR, R.string.drawer_send_to_car, BrowserIcon.CAR,
                detailRes = R.string.browser_send_to_car_detail,
            )
        }

        /**
         * The rows under the primary button. Identical on both surfaces, except that the car's
         * second row also carries the split screen, which is used often enough on the head unit to
         * sit up front rather than behind "More" (what the side pane shows, and which side it is
         * on, lead the "More" list). Entries the browser cannot perform
         * ([BrowserMenuState.unsupported]) are left out.
         */
        fun primaryRows(state: BrowserMenuState): List<List<DrawerItem>> =
            sharedPrimaryRows(state).mapIndexed { index, row ->
                if (index == 1 && state.surface == MenuSurface.CAR) {
                    row.take(row.lastIndex) +
                        DrawerItem(DrawerAction.SPLIT_LAYOUT, R.string.drawer_split, BrowserIcon.SPLIT_LAYOUT) +
                        row.last()
                } else row
            }.map { row -> row.filterNot { it.action in state.unsupported } }.filter { it.isNotEmpty() }

        private fun sharedPrimaryRows(state: BrowserMenuState): List<List<DrawerItem>> = listOf(
            // Navigation first: it is what a menu opened mid-page is usually opened for, and the
            // toolbar it duplicates auto-hides.
            listOf(
                DrawerItem(DrawerAction.NAV_BACK, R.string.drawer_back, BrowserIcon.BACK, enabled = state.canGoBack),
                DrawerItem(DrawerAction.RELOAD, R.string.drawer_reload, BrowserIcon.RELOAD),
                DrawerItem(DrawerAction.NAV_FORWARD, R.string.drawer_forward, BrowserIcon.FORWARD, enabled = state.canGoForward),
            ),
            listOf(
                DrawerItem(DrawerAction.BOOKMARKS, R.string.drawer_bookmarks, BrowserIcon.BOOKMARK_LIST),
                DrawerItem(DrawerAction.SETTINGS, R.string.drawer_settings, BrowserIcon.SETTINGS),
                DrawerItem(DrawerAction.MORE, R.string.drawer_more, BrowserIcon.MORE),
            ),
        )

        /** The switches on the primary sheet; a switch because the state is what each reports. */
        fun desktopToggle(state: BrowserMenuState): DrawerItem = DrawerItem(
            DrawerAction.TOGGLE_DESKTOP, R.string.drawer_request_desktop, BrowserIcon.DESKTOP_MODE, on = state.isDesktop
        )

        /**
         * Sits directly below [desktopToggle] on the primary sheet. Promoted out of "More" (where it
         * used to be the car's only entry point, see [carMoreItems]) so leaving fullscreen never
         * needs a second trip through the menu on either surface.
         */
        fun fullscreenToggle(state: BrowserMenuState): DrawerItem = DrawerItem(
            DrawerAction.TOGGLE_FULLSCREEN, R.string.drawer_fullscreen, BrowserIcon.FULLSCREEN_ENTER, on = state.fullscreen
        )

        /** Everything that did not earn a place on the primary sheet, reached through "More". */
        fun moreItems(surface: MenuSurface = MenuSurface.CAR): List<DrawerItem> = when (surface) {
            MenuSurface.CAR -> carMoreItems()
            MenuSurface.PHONE -> phoneMoreItems()
        }

        /** The "More" list for [state]: its surface's list, less what that browser cannot do. */
        fun moreItems(state: BrowserMenuState): List<DrawerItem> = when (state.surface) {
            MenuSurface.CAR -> carMoreItems(state).filterNot { it.action in state.unsupported }
            MenuSurface.PHONE -> phoneMoreItems()
        }

        /**
         * Every entry of the car menu in one list, in sheet order: the primary button, the two
         * rows of three, the two switches, everything behind "More", and Exit. The Android Auto
         * template browser draws this as its two-page sheet; Bridge Web and the Duo Screen pane
         * show it as one list. Same items, same order, same names on all three.
         */
        fun carMenu(state: BrowserMenuState): List<DrawerItem> {
            val car = state.copy(surface = MenuSurface.CAR)
            val items = buildList {
                add(primaryAction(car))
                primaryRows(car).flatten().filter { it.action != DrawerAction.MORE }.forEach(::add)
                add(desktopToggle(car))
                add(fullscreenToggle(car))
                addAll(moreItems(car))
                add(DrawerItem(DrawerAction.APP_HOME, R.string.drawer_exit, BrowserIcon.APP_HOME))
            }
            return items.filterNot { it.action in car.unsupported }.distinctBy { it.action }
        }

        private fun carMoreItems(state: BrowserMenuState = BrowserMenuState()): List<DrawerItem> = listOf(
            // Fullscreen no longer lives here: it is a primary-sheet toggle next to Desktop (see
            // [fullscreenToggle]), so it is never buried behind "More" on either surface.
            // The split layout itself is on the primary sheet (see [primaryRows]); the rest of the
            // split screen leads this list.
            DrawerItem(DrawerAction.SIDE_SHOW_PAGE, R.string.drawer_side_show_page, BrowserIcon.SPLIT_LAYOUT),
            // Listed whether or not a split is up, so it is always in the same place; it can only
            // act while one is.
            DrawerItem(
                DrawerAction.SWAP_SPLIT_SIDES, R.string.drawer_swap_sides, BrowserIcon.SPLIT_LAYOUT,
                enabled = state.splitActive,
            ),
            DrawerItem(DrawerAction.NAVIGATE_MAPS, R.string.drawer_navigate_maps, BrowserIcon.CAR),
            DrawerItem(
                DrawerAction.PIN_TOOLBAR, R.string.drawer_pin_toolbar, BrowserIcon.MENU,
                value = if (state.pinnedToolbar) "✓" else "", on = state.pinnedToolbar,
            ),
            DrawerItem(DrawerAction.MIRROR_PHONE, R.string.drawer_mirror_phone, BrowserIcon.CAR),
            DrawerItem(DrawerAction.NEW_TAB, R.string.drawer_new_tab, BrowserIcon.ADD),
            DrawerItem(DrawerAction.HOME, R.string.drawer_start_page, BrowserIcon.HOME_PAGE),
            DrawerItem(DrawerAction.HISTORY, R.string.drawer_history, BrowserIcon.HISTORY),
            DrawerItem(DrawerAction.DOWNLOADS, R.string.drawer_downloads, BrowserIcon.DOWNLOAD),
            DrawerItem(DrawerAction.BOOKMARK_PAGE, R.string.drawer_bookmark, BrowserIcon.BOOKMARK_ADD),
            DrawerItem(DrawerAction.FIND_IN_PAGE, R.string.drawer_find, BrowserIcon.SEARCH),
            DrawerItem(DrawerAction.COPY_URL, R.string.drawer_copy_url, BrowserIcon.COPY),
            // Always offered. Probing the clipboard to decide whether to show this entry does not
            // work on a car surface: Android denies clipboard reads to an app that is not focused
            // on the phone, so the probe always failed and the entry never appeared. Whether there
            // is anything to paste is decided when it is tapped.
            DrawerItem(DrawerAction.PASTE_AND_GO, R.string.drawer_paste_and_go, BrowserIcon.PASTE),
            DrawerItem(DrawerAction.OPEN_EXTERNAL, R.string.drawer_external, BrowserIcon.OPEN_EXTERNAL),
            DrawerItem(DrawerAction.ZOOM_IN, R.string.drawer_zoom_in, BrowserIcon.ADD),
            DrawerItem(DrawerAction.ZOOM_OUT, R.string.drawer_zoom_out, BrowserIcon.REMOVE),
            DrawerItem(DrawerAction.AGENT, R.string.drawer_agent, BrowserIcon.AGENT),
            DrawerItem(DrawerAction.MEDIA_CENTER, R.string.drawer_media, BrowserIcon.MUSIC),
            DrawerItem(DrawerAction.NOW_PLAYING, R.string.drawer_now_playing, BrowserIcon.PLAY),
            DrawerItem(DrawerAction.MEDIA_LIBRARY, R.string.drawer_library, BrowserIcon.LIBRARY),
            DrawerItem(DrawerAction.CLEAR_DATA, R.string.drawer_clear_data, BrowserIcon.DELETE),
            DrawerItem(DrawerAction.DIAGNOSTICS, R.string.drawer_about, BrowserIcon.INFO),
        )

        /** The phone's secondary list, in the order [MoreActionsSheet] draws it. */
        private fun phoneMoreItems(): List<DrawerItem> = listOf(
            DrawerItem(DrawerAction.RECEIVE_FROM_CAR, R.string.drawer_get_from_car, BrowserIcon.RECEIVE),
            DrawerItem(DrawerAction.FIND_IN_PAGE, R.string.drawer_find, BrowserIcon.SEARCH),
            DrawerItem(DrawerAction.COPY_URL, R.string.drawer_copy_url, BrowserIcon.COPY),
            DrawerItem(DrawerAction.PASTE_AND_GO, R.string.drawer_paste_and_go, BrowserIcon.PASTE),
            DrawerItem(DrawerAction.OPEN_EXTERNAL, R.string.drawer_external, BrowserIcon.OPEN_EXTERNAL),
            DrawerItem(DrawerAction.HOME, R.string.drawer_start_page, BrowserIcon.HOME_PAGE),
            DrawerItem(DrawerAction.HISTORY, R.string.drawer_history, BrowserIcon.HISTORY),
            DrawerItem(DrawerAction.DOWNLOADS, R.string.drawer_downloads, BrowserIcon.DOWNLOAD),
            DrawerItem(DrawerAction.SUPPORT, R.string.drawer_support, BrowserIcon.SUPPORT),
            DrawerItem(DrawerAction.LICENSES, R.string.drawer_licenses, BrowserIcon.LICENSES),
            DrawerItem(DrawerAction.GITHUB, R.string.drawer_github, BrowserIcon.CODE),
            DrawerItem(DrawerAction.ZOOM_IN, R.string.drawer_zoom_in, BrowserIcon.ADD),
            DrawerItem(DrawerAction.ZOOM_OUT, R.string.drawer_zoom_out, BrowserIcon.REMOVE),
            // Phone only: the quick menu's own two extra rows (see [BrowserMenuSheet]), with no car
            // equivalent — the car's queue and ad-block controls live in Media Center and Settings.
            DrawerItem(DrawerAction.ADD_TO_QUEUE, R.string.send_queue_title, BrowserIcon.ADD),
            DrawerItem(DrawerAction.TOGGLE_AD_BLOCK, R.string.car_browser_block_ads, BrowserIcon.CLOSE),
        )

        /** Fixed at three so the primary sheet keeps the phone sheet's proportions on every panel. */
        const val PRIMARY_COLUMNS = 3

        fun create(
            sizes: AutoUiSizes,
            viewport: BrowserViewport,
            state: BrowserMenuState,
            more: Boolean = false,
            scrollOffset: Float = 0f,
        ): BrowserDrawerModel {
            val gap = sizes.contentGap
            val tileGap = sizes.menuTileGap

            // Below the toolbar, never under it. See the class doc, rule 1.
            val top = viewport.top + sizes.toolbarHeight(viewport.height)
            val bottom = (viewport.top + viewport.height - gap).coerceAtLeast(top + 1f)
            val available = (viewport.width - gap * 2f).coerceAtLeast(1f)
            val width = available.coerceAtMost(sizes.menuSheetMaxWidth)
            val left = viewport.left + (viewport.width - width) / 2f
            val panel = Box(left, top, left + width, bottom)

            val innerLeft = panel.left + sizes.horizontalPadding
            val innerRight = panel.right - sizes.horizontalPadding
            val innerWidth = (innerRight - innerLeft).coerceAtLeast(1f)

            val gripWidth = (panel.width * 0.16f).coerceAtLeast(sizes.touchTarget)
            val gripHeight = sizes.dp(4f)
            val grip = Box(
                panel.centerX - gripWidth / 2f, panel.top + gap,
                panel.centerX + gripWidth / 2f, panel.top + gap + gripHeight
            )

            // The header gives way to the content on a short panel. Floored so it still carries a
            // hittable close button.
            val headerHeight = (panel.height * 0.15f)
                .coerceIn(sizes.dp(AutoUiSizes.MIN_HEADER_HEIGHT_DP), sizes.touchTarget * 1.3f)
            val header = Box(innerLeft, grip.bottom + gap * 0.5f, innerRight, grip.bottom + gap * 0.5f + headerHeight)
            val headerBottom = header.bottom

            // Round, like the phone's ✕, and never smaller than a usable target.
            val roundSide = (headerHeight - gap * 0.5f).coerceIn(sizes.touchTarget * 0.75f, sizes.touchTarget)
            fun roundAt(right: Float) = Box(
                right - roundSide, header.centerY - roundSide / 2f, right, header.centerY + roundSide / 2f
            )
            val closeButton = roundAt(header.right)

            // Exit leaves the browser for the dashboard: the car's only way out, so it sits in the
            // header on both lists rather than behind a tap.
            val headerLinks = ArrayList<DrawerRow>(2)
            val exitWidth = (sizes.touchTarget * 1.7f).coerceAtMost(header.width * 0.3f)
            val exitBox = Box(
                closeButton.left - gap - exitWidth, closeButton.top,
                closeButton.left - gap, closeButton.bottom
            )
            headerLinks += DrawerRow(DrawerItem(DrawerAction.APP_HOME, R.string.drawer_exit, BrowserIcon.APP_HOME), exitBox, DrawerKind.PILL)
            var titleLeft = header.left
            if (more) {
                // The phone's "More actions" sheet opens with a circular back arrow; so does this.
                val back = Box(header.left, closeButton.top, header.left + roundSide, closeButton.bottom)
                headerLinks.add(0, DrawerRow(DrawerItem(DrawerAction.BACK_TO_MENU, R.string.drawer_back, BrowserIcon.BACK), back, DrawerKind.ROUND))
                titleLeft = back.right + gap * 1.5f
            }

            val contentTop = headerBottom + gap
            val contentBottom = panel.bottom - gap
            val visibleHeight = (contentBottom - contentTop).coerceAtLeast(1f)

            val tileWant = sizes.dp(AutoUiSizes.MENU_TILE_HEIGHT_DP)
            val tileFloor = sizes.dp(AutoUiSizes.MIN_TILE_HEIGHT_DP)

            val itemRows: List<List<DrawerItem>>
            val columns: Int
            if (more) {
                // As many columns as the width allows, and more once the rows would not fit at
                // their minimum height — a scroll-free list beats wide tiles.
                val items = moreItems(state)
                var cols = sizes.menuColumns(innerWidth).coerceAtLeast(PRIMARY_COLUMNS)
                fun rowsFor(c: Int) = (items.size + c - 1) / c
                while (cols < AutoUiSizes.MENU_COLUMNS_MAX &&
                    rowsFor(cols) * tileFloor + tileGap * (rowsFor(cols) - 1) > visibleHeight
                ) cols++
                columns = cols
                itemRows = items.chunked(cols)
            } else {
                columns = PRIMARY_COLUMNS
                itemRows = primaryRows(state)
            }

            // Bands, top to bottom, each (preferred, minimum). See the class doc, rule 3.
            val bands = ArrayList<Pair<Float, Float>>()
            if (!more) {
                bands += sizes.touchTarget to sizes.touchTarget * 0.8f // address
                bands += sizes.dp(64f) to sizes.touchTarget // primary button
            }
            repeat(itemRows.size) { bands += tileWant to tileFloor }
            if (!more) repeat(2) { bands += sizes.touchTarget to sizes.touchTarget * 0.8f } // desktop + fullscreen switches

            // Fixed chrome: a gap after the address, one either side of each divider (drawn in the
            // middle of a doubled gap, as the phone's divider margins do), and the gaps between rows.
            val dividerCount = if (more) 0 else 2
            // The gap between the two stacked toggle rows (Desktop, Fullscreen), present only when
            // there are two of them — i.e. never on the "more" list.
            val toggleGap = if (more) 0f else tileGap
            val chrome = (if (more) 0f else gap) + dividerCount * gap * 2f + tileGap * (itemRows.size - 1) + toggleGap
            val heights = distribute(bands, (visibleHeight - chrome).coerceAtLeast(0f))

            val contentHeight = heights.sum() + chrome
            val maxScroll = (contentHeight - visibleHeight).coerceAtLeast(0f)
            val offset = scrollOffset.coerceIn(0f, maxScroll)

            var cursor = contentTop - offset
            var band = 0
            fun take(): Float = heights[band++]
            val dividers = ArrayList<Float>(dividerCount)
            fun divider() {
                dividers += cursor + gap
                cursor += gap * 2f
            }

            var address: DrawerAddress? = null
            var primary: DrawerRow? = null
            if (!more) {
                val addressHeight = take()
                val addressBox = Box(innerLeft, cursor, innerRight, cursor + addressHeight)
                val side = (addressHeight - gap * 0.5f).coerceAtMost(sizes.touchTarget)
                val goBox = Box(
                    addressBox.right - gap * 0.5f - side, addressBox.centerY - side / 2f,
                    addressBox.right - gap * 0.5f, addressBox.centerY + side / 2f
                )
                val clearBox = Box(
                    goBox.left - gap * 0.5f - side, addressBox.centerY - side / 2f,
                    goBox.left - gap * 0.5f, addressBox.centerY + side / 2f
                )
                address = DrawerAddress(addressBox, BrowserDisplayUrl.compact(state.url), state.secure, clearBox, goBox)
                cursor = addressBox.bottom + gap

                val primaryHeight = take()
                primary = DrawerRow(
                    primaryAction(state), Box(innerLeft, cursor, innerRight, cursor + primaryHeight),
                    DrawerKind.PRIMARY
                )
                cursor += primaryHeight
                divider()
            }

            val tiles = ArrayList<DrawerRow>()
            itemRows.forEachIndexed { rowIndex, row ->
                // The "More" grid keeps a fixed column width so a short last row stays aligned;
                // a primary row shares the width between its own tiles (the car's second row has four).
                val rowColumns = if (more) columns else row.size.coerceAtLeast(1)
                val tileWidth = (innerWidth - tileGap * (rowColumns - 1)) / rowColumns
                val height = take()
                row.forEachIndexed { column, item ->
                    val tileLeft = innerLeft + column * (tileWidth + tileGap)
                    tiles += DrawerRow(
                        item, Box(tileLeft, cursor, tileLeft + tileWidth, cursor + height), DrawerKind.TILE
                    )
                }
                cursor += height
                if (rowIndex < itemRows.lastIndex) cursor += tileGap
            }

            val toggles = if (more) emptyList() else {
                divider()
                val items = listOf(desktopToggle(state), fullscreenToggle(state))
                items.mapIndexed { index, item ->
                    val height = take()
                    val box = Box(innerLeft, cursor, innerRight, cursor + height)
                    cursor = box.bottom
                    if (index < items.lastIndex) cursor += tileGap
                    DrawerRow(item, box, DrawerKind.TOGGLE)
                }
            }

            return BrowserDrawerModel(
                sizes = sizes,
                panel = panel,
                title = if (more) "More actions" else state.appName,
                subtitle = if (more) "" else state.pageTitle,
                grip = grip,
                header = header,
                titleLeft = titleLeft,
                closeButton = closeButton,
                headerLinks = headerLinks,
                address = address,
                primary = primary,
                tiles = tiles,
                dividers = dividers,
                toggles = toggles,
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

    /** The last thing on the sheet, for "scrolled to the end" checks. */
    val contentBottom: Float get() =
        (toggles.lastOrNull() ?: tiles.lastOrNull() ?: primary)?.bounds?.bottom ?: headerBottom

    /** Everything that can be tapped, in the order hit testing resolves them. */
    val rows: List<DrawerRow>
        get() = buildList {
            addAll(headerLinks)
            address?.let { address ->
                add(DrawerRow(DrawerItem(DrawerAction.ADDRESS_CLEAR, R.string.drawer_clear, BrowserIcon.CLOSE), address.clear, DrawerKind.ROUND))
                add(DrawerRow(DrawerItem(DrawerAction.ADDRESS_KEYBOARD, R.string.drawer_search, BrowserIcon.SEARCH), address.go, DrawerKind.ROUND))
                add(DrawerRow(DrawerItem(DrawerAction.ADDRESS_KEYBOARD, R.string.drawer_address, BrowserIcon.LOCK), address.bounds, DrawerKind.ADDRESS))
            }
            primary?.let { add(it) }
            addAll(tiles)
            addAll(toggles)
        }

    /** True when a tap landed on the header's close button. Checked before [actionAt]. */
    fun hitsClose(x: Float, y: Float): Boolean = closeButton.contains(x, y)

    /**
     * The entry under a tap, or null when the tap fell on the sheet's background.
     *
     * A disabled entry resolves to null rather than to its action: Back with no history behind it
     * is drawn so the row keeps its shape, but it must not swallow the tap and do nothing.
     */
    fun rowAt(x: Float, y: Float): DrawerRow? {
        if (!panel.contains(x, y)) return null
        // The header's own buttons never scroll, so they are resolved before the scroll boundary.
        headerLinks.firstOrNull { it.bounds.contains(x, y) }?.let { return it }
        // Entries scrolled under the header must not be tappable even though their box overlaps it.
        if (y < headerBottom) return null
        return rows.firstOrNull { it.item.enabled && it !in headerLinks && it.bounds.contains(x, y) }
    }

    /** Convenience for call sites that only need to know what a tap means. */
    fun actionAt(x: Float, y: Float): DrawerAction? =
        if (hitsClose(x, y)) DrawerAction.CLOSE_SHEET else rowAt(x, y)?.item?.action
}
