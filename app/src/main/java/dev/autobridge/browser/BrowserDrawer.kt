package dev.autobridge.browser

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

/**
 * A laid-out row with the box it occupies in surface coordinates. [sectionTitle] is non-null only
 * on the first row of a section, so the renderer can draw group headings without searching the
 * section list for every row on every frame.
 */
data class DrawerRow(val item: DrawerItem, val bounds: Box, val sectionTitle: String? = null)

/**
 * Content and geometry for the navigation drawer.
 *
 * The drawer is an **overlay**: it is composited over the page and never reduces the viewport the
 * WebView is laid out against. A side panel that pushes content would change the page's width on
 * every open and close, reflowing the site — the exact class of movement this work removes. That
 * trade-off is taken deliberately on every screen size, including wide ones.
 *
 * Geometry is pure data so it can be asserted at each supported head unit resolution in a JVM test.
 */
class BrowserDrawerModel private constructor(
    val sizes: AutoUiSizes,
    val panel: Box,
    val sections: List<DrawerSection>,
    val rows: List<DrawerRow>,
    val headerBottom: Float,
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
            DrawerSection(
                "Browser",
                listOf(
                    DrawerItem(DrawerAction.NEW_TAB, "New tab", "+"),
                    DrawerItem(DrawerAction.TABS, "Tabs", "▣", tabCount.toString()),
                    DrawerItem(DrawerAction.HOME, "Home", "⌂"),
                    DrawerItem(DrawerAction.BOOKMARKS, "Bookmarks", "☆"),
                    DrawerItem(DrawerAction.HISTORY, "History", "↺"),
                    DrawerItem(DrawerAction.DOWNLOADS, "Downloads", "↓"),
                )
            ),
            DrawerSection(
                "Tools",
                listOf(
                    DrawerItem(DrawerAction.ADDRESS_KEYBOARD, "Keyboard / address", "⌨"),
                    DrawerItem(DrawerAction.FIND_IN_PAGE, "Find in page", "⌕"),
                    DrawerItem(
                        DrawerAction.TOGGLE_DESKTOP, "Desktop site", "□",
                        if (isDesktop) "On" else "Off"
                    ),
                    DrawerItem(DrawerAction.RELOAD, "Reload", "↻"),
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
                    DrawerItem(DrawerAction.AGENT, "Agent", "❖"),
                    DrawerItem(DrawerAction.COPY_URL, "Copy URL", "⧉"),
                    // Always offered. Probing the clipboard to decide whether to show this row
                    // does not work on a car surface: Android denies clipboard reads to an app that
                    // is not focused on the phone, so the probe always failed, the row never
                    // appeared, and every drawer open logged a denial. Whether there is anything to
                    // paste is therefore decided when the row is tapped.
                    DrawerItem(DrawerAction.PASTE_AND_GO, "Paste & go", "⎘"),
                    DrawerItem(DrawerAction.BOOKMARK_PAGE, "Bookmark page", "★"),
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
        ): BrowserDrawerModel {
            val width = sizes.drawerWidth(viewport.width)
            val left = viewport.left.toFloat()
            val top = viewport.top.toFloat()
            val bottom = top + viewport.height
            val panel = Box(left, top, left + width, bottom)

            val rowHeight = sizes.touchTarget
            val headerHeight = sizes.dp(26f)
            val headerBottom = top + sizes.toolbarHeight(viewport.height)

            // Content is measured first so the scroll offset can be clamped before any row is placed.
            val measured = sections.sumOf { headerHeight.toDouble() + it.items.size * rowHeight }.toFloat()
            val visible = (bottom - headerBottom - sizes.contentGap).coerceAtLeast(rowHeight)
            val maxScroll = (measured - visible).coerceAtLeast(0f)
            val offset = scrollOffset.coerceIn(0f, maxScroll)

            val rows = ArrayList<DrawerRow>()
            var cursor = headerBottom + sizes.contentGap - offset
            sections.forEach { section ->
                cursor += headerHeight
                section.items.forEachIndexed { index, item ->
                    rows += DrawerRow(
                        item,
                        Box(left, cursor, left + width, cursor + rowHeight),
                        section.title.takeIf { index == 0 }
                    )
                    cursor += rowHeight
                }
            }
            return BrowserDrawerModel(
                sizes, panel, sections, rows, headerBottom, measured, visible, offset
            )
        }
    }

    val maxScroll: Float get() = (contentHeight - visibleHeight).coerceAtLeast(0f)

    /** The drawer row under a tap, or null when the tap fell on the panel background. */
    fun rowAt(x: Float, y: Float): DrawerRow? {
        if (!panel.contains(x, y)) return null
        // Rows scrolled under the header must not be tappable even though their box overlaps it.
        if (y < headerBottom) return null
        return rows.firstOrNull { it.bounds.contains(x, y) }
    }
}
