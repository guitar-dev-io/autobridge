package dev.autobridge.browser

import kotlin.math.abs
import kotlin.math.ln

/**
 * Everything the drawer can do. Each entry maps to logic that already exists elsewhere in the app
 * (a [CarWebRenderer] call, a store, or a Car App screen); the drawer is presentation only and
 * implements no browser behaviour of its own.
 */
enum class DrawerAction {
    NEW_TAB, TABS, BOOKMARKS, HISTORY, DOWNLOADS,
    MEDIA_CENTER, NOW_PLAYING, MEDIA_LIBRARY,
    ADDRESS_KEYBOARD, COPY_URL, PASTE_AND_GO, FIND_IN_PAGE, AGENT,
    TOGGLE_DESKTOP, ZOOM_IN, ZOOM_OUT, RELOAD, HOME,
    BOOKMARK_PAGE, OPEN_EXTERNAL, SETTINGS, CLEAR_DATA, DIAGNOSTICS,
    /**
     * Leaves the browser entirely and returns to AutoBridge's main dashboard — the car surface's
     * only route back to Home, since [CarBrowserScreen][dev.autobridge.car.CarBrowserScreen]
     * deliberately carries no header Back action of its own (see the comment on its
     * `onGetTemplate()`). Distinct from [HOME], which stays inside the browser and loads the
     * configured start page.
     */
    APP_HOME,
    /** Opens the secondary "More" drawer list; see [BrowserDrawerModel.moreSectionsFor]. */
    MORE,
    /** Returns from the "More" list to the primary drawer list. */
    BACK_TO_MENU,
}

/** A tappable drawer row. */
data class DrawerItem(
    val action: DrawerAction,
    val label: String,
    val glyph: String,
    /** Right-aligned state text, e.g. "On" / "3". Blank when the row has no state. */
    val value: String = "",
)

/** A labelled group of rows. */
data class DrawerSection(val title: String, val items: List<DrawerItem>)

/** A laid-out tile with the box it occupies in surface coordinates. */
data class DrawerRow(val item: DrawerItem, val bounds: Box)

/**
 * Content and geometry for the navigation drawer.
 *
 * The drawer is an **overlay**: it is composited over the page and never reduces the viewport the
 * WebView is laid out against. A panel that pushes content would change the page's width on every
 * open and close, reflowing the site — the exact class of movement this work removes. That
 * trade-off is taken deliberately on every screen size, including wide ones.
 *
 * Entries are laid out as a **grid of tiles**, not a list of rows, so each one is a large target
 * rather than the 46dp minimum.
 *
 * Two rules make the same item list work on every head unit:
 *
 * 1. **The sheet starts below the toolbar, never under it.** It used to begin one margin below the
 *    top of the viewport, inside the band the toolbar occupies. Since [CarWebRenderer] drew the
 *    toolbar *after* the sheet, the bar painted over the sheet's header: the close button ended up
 *    73% hidden behind the fullscreen icon, and the header title was covered outright. Taps were
 *    worse than the drawing — the top ~16dp of the bar fell outside the panel and dismissed the
 *    sheet, while the rest of it (the part holding back, forward, reload and the address pill) fell
 *    *inside* the panel and was swallowed, so visibly-lit buttons did nothing. Starting below the
 *    bar makes the header always visible and gives every tap outside the panel one meaning.
 *
 * 2. **The grid is sized from the box, not authored and hoped for.** [chooseGrid] picks the column
 *    and row count whose tiles actually fit the space left after the header, so the sheet does not
 *    scroll unless even [AutoUiSizes.MIN_TILE_HEIGHT_DP] cannot be made to fit. Scrolling was the
 *    real usability failure: this file's own history records that a tap drifting a few px during a
 *    scrollable list is read as a scroll and the entry never fires, and the entry stranded below
 *    the fold was "More" — the only route to the rest of the menu.
 *
 * Geometry is pure data so it can be asserted at each supported head unit resolution in a JVM test.
 */
class BrowserDrawerModel private constructor(
    val sizes: AutoUiSizes,
    val panel: Box,
    /** Header caption naming which list is open, e.g. "Menu" or "More". */
    val title: String,
    val sections: List<DrawerSection>,
    val rows: List<DrawerRow>,
    val headerBottom: Float,
    /**
     * The sheet's own close target, in the header. The sheet covers most of the viewport, so "tap
     * outside to close" asked the user to hit a band a few dp wide while the car moves — in
     * practice the drawer could not be dismissed except by picking an action. A button in a fixed
     * place is the way out.
     */
    val closeButton: Box,
    val contentHeight: Float,
    val visibleHeight: Float,
    val scrollOffset: Float,
) {
    companion object {
        /**
         * Builds the section list from live browser state. Every entry resolves to an existing
         * destination or renderer call; nothing here is a placeholder.
         */
        fun sectionsFor(
            tabCount: Int,
            isDesktop: Boolean,
        ): List<DrawerSection> = listOf(
            // Only the rows used every session live in the always-visible list. The previous
            // 4-section / 20-row layout forced scrolling on most head units, and a tap that
            // drifted a few px while scrolling was read as a scroll gesture instead of a click —
            // the row simply never fired. Everything else moved to a "More" row that opens
            // [moreSectionsFor], reachable from the same drawer without adding scroll to the
            // common case.
            //
            // Nothing here repeats a toolbar button. Reload, the address pill and fullscreen are
            // already one tap away on the bar above the page, and offering them a second time in
            // the menu spent the largest, easiest-to-hit targets on the surface re-stating what
            // the user could already see. They live in [moreSectionsFor] instead, so they are
            // still reachable when the toolbar has faded out, just not duplicated.
            DrawerSection(
                "Browser",
                listOf(
                    DrawerItem(DrawerAction.NEW_TAB, "New tab", "+"),
                    DrawerItem(DrawerAction.TABS, "Tabs", "▣", tabCount.toString()),
                    DrawerItem(DrawerAction.HOME, "Home", "⌂"),
                    DrawerItem(DrawerAction.BOOKMARKS, "Bookmarks", "☆"),
                    DrawerItem(DrawerAction.HISTORY, "History", "↺"),
                    DrawerItem(DrawerAction.DOWNLOADS, "Downloads", "↓"),
                    // The car surface has no header Back for this screen (see CarBrowserScreen);
                    // this is the way out of the browser back to AutoBridge's main dashboard.
                    DrawerItem(DrawerAction.APP_HOME, "AutoBridge Home", "⏏"),
                )
            ),
            DrawerSection(
                "Tools",
                listOf(
                    DrawerItem(DrawerAction.BOOKMARK_PAGE, "Bookmark page", "★"),
                    DrawerItem(DrawerAction.FIND_IN_PAGE, "Find in page", "⌕"),
                    DrawerItem(
                        DrawerAction.TOGGLE_DESKTOP, "Desktop site", "□",
                        if (isDesktop) "On" else "Off"
                    ),
                    DrawerItem(DrawerAction.MORE, "More", "⋯"),
                )
            ),
        )

        /**
         * Rows that used to live in the drawer's Media/Tools/Settings sections, now reached via the
         * "More" row instead of always being on screen. [DrawerAction.MORE] itself is omitted here
         * on purpose: it belongs only to the primary list, so tapping it always goes forward.
         */
        fun moreSectionsFor(isDesktop: Boolean): List<DrawerSection> = listOf(
            DrawerSection(
                "More",
                listOf(
                    DrawerItem(DrawerAction.BACK_TO_MENU, "Back", "‹"),
                )
            ),
            DrawerSection(
                "Media",
                listOf(
                    DrawerItem(DrawerAction.MEDIA_CENTER, "Media", "♪"),
                    DrawerItem(DrawerAction.NOW_PLAYING, "Now playing", "▶"),
                    DrawerItem(DrawerAction.MEDIA_LIBRARY, "Files & library", "▤"),
                )
            ),
            DrawerSection(
                "Tools",
                listOf(
                    // The toolbar's own duplicates: on the bar while it is shown, here for when it
                    // has faded out and the user would rather not recall it first.
                    DrawerItem(DrawerAction.ADDRESS_KEYBOARD, "Keyboard / address", "⌨"),
                    DrawerItem(DrawerAction.RELOAD, "Reload", "↻"),
                    DrawerItem(DrawerAction.AGENT, "Agent", "❖"),
                    DrawerItem(DrawerAction.COPY_URL, "Copy URL", "⧉"),
                    // Always offered. Probing the clipboard to decide whether to show this row
                    // does not work on a car surface: Android denies clipboard reads to an app that
                    // is not focused on the phone, so the probe always failed, the row never
                    // appeared, and every drawer open logged a denial. Whether there is anything to
                    // paste is therefore decided when the row is tapped.
                    DrawerItem(DrawerAction.PASTE_AND_GO, "Paste & go", "⎘"),
                    DrawerItem(DrawerAction.ZOOM_IN, "Zoom in", "+"),
                    DrawerItem(DrawerAction.ZOOM_OUT, "Zoom out", "−"),
                )
            ),
            DrawerSection(
                "Settings",
                listOf(
                    DrawerItem(DrawerAction.SETTINGS, "Browser settings", "⚙"),
                    DrawerItem(DrawerAction.CLEAR_DATA, "Clear browsing data", "⌧"),
                    DrawerItem(DrawerAction.OPEN_EXTERNAL, "Open in phone browser", "↗"),
                    DrawerItem(DrawerAction.DIAGNOSTICS, "About & diagnostics", "ℹ"),
                )
            ),
        )

        fun create(
            sizes: AutoUiSizes,
            viewport: BrowserViewport,
            sections: List<DrawerSection>,
            scrollOffset: Float = 0f,
            title: String = "Menu",
        ): BrowserDrawerModel {
            // One flat grid rather than a heading per section. A drawn heading cost a whole
            // 26dp band plus its gap for a word ("BROWSER", "TOOLS") that eleven self-labelled
            // tiles already imply, and on a short panel that band was the difference between the
            // last row fitting and falling off the bottom. Sections still exist — they order the
            // list so related entries stay adjacent — they are just no longer drawn.
            val items = sections.flatMap { it.items }
            val margin = sizes.contentGap

            // Below the toolbar, never under it. See the class doc, rule 1.
            val top = viewport.top + sizes.toolbarHeight(viewport.height)
            val left = viewport.left + margin
            val right = viewport.left + viewport.width - margin
            val bottom = viewport.top + viewport.height - margin
            val panel = Box(left, top, right.coerceAtLeast(left + 1f), bottom.coerceAtLeast(top + 1f))

            // The header gives way to the tiles on a short panel instead of holding a fixed share
            // of a height there is none of. Floored so it can still carry a hittable close button.
            val headerHeight = (panel.height * 0.22f)
                .coerceIn(sizes.dp(AutoUiSizes.MIN_HEADER_HEIGHT_DP), sizes.touchTarget + sizes.contentGap)
            val headerBottom = panel.top + headerHeight

            // Trailing end of the header, the same corner the toolbar's menu button occupies, so
            // opening and closing the sheet happen at roughly the same place on screen.
            val closeSide = (headerHeight - sizes.contentGap * 0.5f).coerceAtMost(sizes.touchTarget)
            val headerCenterY = (panel.top + headerBottom) / 2f
            val closeButton = Box(
                panel.right - sizes.horizontalPadding - closeSide, headerCenterY - closeSide / 2f,
                panel.right - sizes.horizontalPadding, headerCenterY + closeSide / 2f
            )

            val innerLeft = panel.left + sizes.horizontalPadding
            val innerRight = panel.right - sizes.horizontalPadding
            val innerTop = headerBottom + sizes.contentGap
            val innerBottom = panel.bottom - sizes.contentGap
            val innerWidth = (innerRight - innerLeft).coerceAtLeast(1f)
            val innerHeight = (innerBottom - innerTop).coerceAtLeast(1f)

            val gap = sizes.menuTileGap
            val grid = chooseGrid(sizes, items.size, innerWidth, innerHeight)

            val contentHeight = grid.rows * grid.tileHeight + gap * (grid.rows - 1)
            val gridWidth = grid.columns * grid.tileWidth + gap * (grid.columns - 1)
            val maxScroll = (contentHeight - innerHeight).coerceAtLeast(0f)
            val offset = scrollOffset.coerceIn(0f, maxScroll)

            // Centred in whatever the grid did not need, so a widescreen unit gets a balanced
            // block of buttons rather than a cluster in the top-left corner.
            val gridLeft = innerLeft + (innerWidth - gridWidth) / 2f
            val gridTop = innerTop + ((innerHeight - contentHeight) / 2f).coerceAtLeast(0f) - offset

            val rows = ArrayList<DrawerRow>(items.size)
            items.forEachIndexed { index, item ->
                val column = index % grid.columns
                val row = index / grid.columns
                val tileLeft = gridLeft + column * (grid.tileWidth + gap)
                val tileTop = gridTop + row * (grid.tileHeight + gap)
                rows += DrawerRow(
                    item,
                    Box(tileLeft, tileTop, tileLeft + grid.tileWidth, tileTop + grid.tileHeight)
                )
            }
            return BrowserDrawerModel(
                sizes, panel, title, sections, rows, headerBottom, closeButton,
                contentHeight, innerHeight, offset
            )
        }

        /** A resolved grid: how many tiles across and down, and how large each one is. */
        private data class Grid(
            val columns: Int,
            val rows: Int,
            val tileWidth: Float,
            val tileHeight: Float,
        )

        /**
         * Picks the grid that fits [count] tiles inside [innerWidth] x [innerHeight] without
         * scrolling, preferring the one whose tiles come closest to the authored tile proportions.
         *
         * Every column count from the widest the panel allows down to one is tried, because fewer
         * columns means more rows and the two trade against each other — on a short wide panel the
         * answer is more columns, on a tall narrow one fewer. Only if no arrangement keeps tiles at
         * [AutoUiSizes.MIN_TILE_WIDTH_DP] x [AutoUiSizes.MIN_TILE_HEIGHT_DP] does the sheet fall
         * back to a scrolling grid, which is the correct way to degrade rather than the default.
         */
        private fun chooseGrid(
            sizes: AutoUiSizes,
            count: Int,
            innerWidth: Float,
            innerHeight: Float,
        ): Grid {
            val tiles = count.coerceAtLeast(1)
            val gap = sizes.menuTileGap
            val minWidth = sizes.dp(AutoUiSizes.MIN_TILE_WIDTH_DP)
            val minHeight = sizes.dp(AutoUiSizes.MIN_TILE_HEIGHT_DP)
            val maxWidth = sizes.dp(AutoUiSizes.MENU_TILE_WIDTH_DP * AutoUiSizes.MAX_TILE_SCALE)
            val maxHeight = sizes.dp(AutoUiSizes.MENU_TILE_HEIGHT_DP * AutoUiSizes.MAX_TILE_SCALE)
            val targetAspect = AutoUiSizes.MENU_TILE_WIDTH_DP / AutoUiSizes.MENU_TILE_HEIGHT_DP
            val widest = sizes.menuColumns(innerWidth).coerceAtMost(tiles)

            var best: Grid? = null
            var bestScore = Float.MAX_VALUE
            for (columns in widest downTo 1) {
                val rows = (tiles + columns - 1) / columns
                val tileWidth = (innerWidth - gap * (columns - 1)) / columns
                val tileHeight = (innerHeight - gap * (rows - 1)) / rows
                if (tileWidth < minWidth || tileHeight < minHeight) continue
                // Compared in log space so "twice as wide as wanted" and "half as wide" are equally
                // far from the target instead of the ratio favouring one direction.
                val score = abs(ln(tileWidth / tileHeight) - ln(targetAspect))
                if (score < bestScore) {
                    bestScore = score
                    best = Grid(
                        columns, rows,
                        tileWidth.coerceAtMost(maxWidth), tileHeight.coerceAtMost(maxHeight)
                    )
                }
            }
            best?.let { return it }

            // Too little room for any arrangement: fill the width, hold tiles at the floor, scroll.
            val columns = widest
            val rows = (tiles + columns - 1) / columns
            return Grid(columns, rows, (innerWidth - gap * (columns - 1)) / columns, minHeight)
        }
    }

    val maxScroll: Float get() = (contentHeight - visibleHeight).coerceAtLeast(0f)

    /** True when a tap landed on the header's close button. Checked before [rowAt]. */
    fun hitsClose(x: Float, y: Float): Boolean = closeButton.contains(x, y)

    /** The drawer row under a tap, or null when the tap fell on the panel background. */
    fun rowAt(x: Float, y: Float): DrawerRow? {
        if (!panel.contains(x, y)) return null
        // Rows scrolled under the header must not be tappable even though their box overlaps it.
        if (y < headerBottom) return null
        return rows.firstOrNull { it.bounds.contains(x, y) }
    }
}
