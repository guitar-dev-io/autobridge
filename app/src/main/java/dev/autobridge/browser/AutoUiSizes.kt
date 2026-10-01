package dev.autobridge.browser

import kotlin.math.roundToInt

/**
 * Single source of truth for browser chrome sizing, shared by the car [CarWebRenderer] (Canvas
 * pixels) and the phone [BrowserActivity] (View pixels).
 *
 * Every value is authored once in **dp** and resolved through the surface's real density, never
 * through its pixel width. The previous model derived chrome size from `surfaceWidth / 640f`, which
 * made a wide head unit draw proportionally larger icons even when its physical density was low —
 * the "enlarged tablet UI" symptom. Density is the only correct basis: 24dp is the same physical
 * size on an 800x480 @ 160dpi unit and a 1280x720 @ 240dpi unit.
 *
 * Icon *visual* size and *touch target* are deliberately separate fields. A toolbar button occupies
 * [touchTarget] (>= 44dp) while the glyph inside it is [iconMedium] (~22dp), so shrinking the glyph
 * to an infotainment-appropriate density never makes the control harder to hit.
 */
class AutoUiSizes private constructor(
    /** Effective pixels-per-dp for the surface these sizes were resolved against. */
    val density: Float,
) {
    companion object {
        // Authored dp values. Keep this list short: a page that needs a size not listed here should
        // be expressed in terms of one of these, not given its own magic number.
        const val ICON_SMALL_DP = 18f
        const val ICON_MEDIUM_DP = 22f
        const val ICON_LARGE_DP = 28f
        const val TOUCH_TARGET_DP = 46f
        const val TOOLBAR_HEIGHT_DP = 52f
        const val HORIZONTAL_PADDING_DP = 12f
        const val CONTENT_GAP_DP = 8f
        const val CORNER_RADIUS_DP = 10f
        const val DRAWER_WIDTH_DP = 300f
        const val EDGE_REVEAL_DP = 28f
        const val HANDLE_HEIGHT_DP = 18f

        /**
         * Menu tiles. A menu entry is a whole button rather than a text row: a 46dp row is the
         * minimum a finger can hit at rest, which is the wrong target for a control used in a
         * moving car. A tile of this size is roughly four times the area and still fits its label
         * on one line.
         */
        const val MENU_TILE_WIDTH_DP = 104f
        const val MENU_TILE_HEIGHT_DP = 78f
        const val MENU_TILE_GAP_DP = 10f

        /** Column count is derived from the available width, never fixed, and bounded both ways. */
        const val MENU_COLUMNS_MIN = 2
        const val MENU_COLUMNS_MAX = 5

        /**
         * Ceiling on the menu sheet's width.
         *
         * The sheet is authored against a phone's proportions — a header, a full-width address row
         * and three tiles across. Letting it take the full width of a 1920px head unit would stretch
         * those same three tiles to the size of playing cards and put the address row's trailing
         * buttons a hand's width from its leading lock icon. Bounded and centred, a wide panel gets
         * the same sheet a narrow one does, with the page showing either side of it.
         */
        const val MENU_SHEET_MAX_WIDTH_DP = 560f

        /**
         * Floor under the sheet's footer band. It carries the version text and two pills, so it
         * compresses further than a tile row before the sheet gives up and scrolls.
         */
        const val MIN_FOOTER_HEIGHT_DP = 30f

        /**
         * Floor under a menu tile, once the sheet is sized from the box it must fit rather than
         * authored outright ([MENU_TILE_HEIGHT_DP] is the *preferred* height, this is the smallest a
         * tile may be squeezed to before the sheet gives up and scrolls).
         *
         * A fixed 78dp tile made the sheet's content height independent of the panel it was drawn
         * in, so on a short stable area the last row simply fell below the fold with nothing on
         * screen to say it was there — and that row held "More", the only way to reach the rest of
         * the menu. Deriving the tile from the box instead means the same sheet fits a 300dp-tall
         * stable area and a 720dp one.
         *
         * It stays above [TOUCH_TARGET_DP] so the smallest tile the sheet can produce is still a
         * comfortable target, not a bare minimum one.
         */
        const val MIN_TILE_HEIGHT_DP = 48f

        /**
         * The sheet header shrinks with the panel, but never below this — it has to hold a close
         * button that is still hittable at [TOUCH_TARGET_DP] * 0.7.
         */
        const val MIN_HEADER_HEIGHT_DP = 40f

        /**
         * The floating control button. It stays reachable over the page so opening the menu never
         * requires first recalling an auto-hidden toolbar — two taps, one of them on a 28dp strip,
         * became one tap on a 56dp circle.
         */
        const val FAB_SIZE_DP = 56f
        const val FAB_MARGIN_DP = 14f

        /**
         * Phone bottom-sheet proportions, authored to the Android Auto–style mockup rather than to
         * the car surface's compact chrome. The car tiles are small and dense because a head unit
         * is read at arm's length while driving; the phone sheet is held in the hand and the mockup
         * wants generous rounded cards, a tall primary CTA and a clear emphasis ladder. These are
         * kept separate from the car tokens above so changing one surface never silently reshapes
         * the other, and are resolved through [dp] like every other value so they scale with the
         * phone's real density.
         */
        const val SHEET_SIDE_MARGIN_DP = 16f
        const val SHEET_PADDING_DP = 16f
        const val SHEET_SECTION_GAP_DP = 12f
        const val SHEET_CORNER_RADIUS_DP = 26f
        const val SHEET_URL_FIELD_HEIGHT_DP = 56f
        const val SHEET_PRIMARY_CTA_HEIGHT_DP = 92f
        const val SHEET_TILE_HEIGHT_DP = 96f
        const val SHEET_ROW_HEIGHT_DP = 60f
        const val SHEET_CLOSE_BUTTON_DP = 48f
        const val SHEET_LIST_ICON_DP = 44f
        const val SHEET_ICON_DP = 26f

        /**
         * The page fills the surface edge to edge: no inset margin and no rounded corners. Was
         * previously a Fermata-style floating card inset from the edges, but that left visible
         * black borders around the page on the car display, which is not the wanted look.
         */
        const val CARD_MARGIN_DP = 0f
        const val CARD_CORNER_RADIUS_DP = 0f

        /**
         * Density is clamped so an implausible value reported by a head unit cannot scale the whole
         * chrome away. 1.0 covers a 160dpi unit; 3.0 covers the densest realistic panel.
         */
        const val MIN_DENSITY = 0.75f
        const val MAX_DENSITY = 3.0f

        /**
         * The toolbar may never eat more than this share of the surface height. On a 480px-tall
         * panel at a high reported density, an unclamped 52dp toolbar would be a fifth of the
         * screen; this keeps "content first" true on the smallest supported head unit.
         */
        const val MAX_TOOLBAR_HEIGHT_FRACTION = 0.20f

        /** A toolbar shorter than this stops being a reliable touch target. */
        const val MIN_TOOLBAR_HEIGHT_DP = 44f

        /**
         * Upper bound on the system font scale. Text stays readable at larger accessibility
         * settings, but the toolbar cannot grow without limit and break the layout.
         */
        const val MAX_FONT_SCALE = 1.15f

        /** Resolves sizes from a raw density (pixels per dp). */
        fun forDensity(density: Float): AutoUiSizes =
            AutoUiSizes(density.takeIf { it.isFinite() && it > 0f }?.coerceIn(MIN_DENSITY, MAX_DENSITY) ?: 1f)

        /**
         * Resolves sizes for an Android Auto surface from the dpi the host reports via
         * `SurfaceContainer.getDpi()`. This is the car equivalent of a devicePixelRatio and the only
         * density signal that describes the *car* panel; the connected phone's density describes a
         * different screen entirely and must not be used here.
         */
        fun forCarSurface(dpi: Int): AutoUiSizes =
            forDensity(if (dpi > 0) dpi / 160f else 1f)

        /** Clamps a system font scale so text growth cannot cascade into layout growth. */
        fun clampFontScale(fontScale: Float): Float =
            fontScale.takeIf { it.isFinite() && it > 0f }?.coerceIn(1f, MAX_FONT_SCALE) ?: 1f
    }

    fun dp(value: Float): Float = value * density
    fun dpInt(value: Float): Int = (value * density).roundToInt()

    val iconSmall: Float get() = dp(ICON_SMALL_DP)
    val iconMedium: Float get() = dp(ICON_MEDIUM_DP)
    val iconLarge: Float get() = dp(ICON_LARGE_DP)
    val touchTarget: Float get() = dp(TOUCH_TARGET_DP)
    val horizontalPadding: Float get() = dp(HORIZONTAL_PADDING_DP)
    val contentGap: Float get() = dp(CONTENT_GAP_DP)
    val cornerRadius: Float get() = dp(CORNER_RADIUS_DP)
    val edgeReveal: Float get() = dp(EDGE_REVEAL_DP)
    val handleHeight: Float get() = dp(HANDLE_HEIGHT_DP)
    val cardMargin: Float get() = dp(CARD_MARGIN_DP)
    val cardCornerRadius: Float get() = dp(CARD_CORNER_RADIUS_DP)
    val menuSheetMaxWidth: Float get() = dp(MENU_SHEET_MAX_WIDTH_DP)
    val menuTileGap: Float get() = dp(MENU_TILE_GAP_DP)
    val fabSize: Float get() = dp(FAB_SIZE_DP)
    val fabMargin: Float get() = dp(FAB_MARGIN_DP)

    /**
     * How many tiles fit across [availableWidth] px. Derived from the tile's authored dp width so
     * a denser panel does not silently get more, narrower columns.
     */
    fun menuColumns(availableWidth: Float): Int {
        if (availableWidth <= 0f) return MENU_COLUMNS_MIN
        val fit = ((availableWidth + menuTileGap) / (dp(MENU_TILE_WIDTH_DP) + menuTileGap)).toInt()
        return fit.coerceIn(MENU_COLUMNS_MIN, MENU_COLUMNS_MAX)
    }

    /**
     * Toolbar height for a viewport [availableHeight] px tall. Bounded above by the content-first
     * rule and below by the smallest height that still reads as a touch target, so a short panel
     * gets a proportionally smaller toolbar without becoming unusable.
     */
    fun toolbarHeight(availableHeight: Int): Float {
        val preferred = dp(TOOLBAR_HEIGHT_DP)
        if (availableHeight <= 0) return preferred
        return preferred
            .coerceAtMost(availableHeight * MAX_TOOLBAR_HEIGHT_FRACTION)
            .coerceAtLeast(dp(MIN_TOOLBAR_HEIGHT_DP))
    }

    /**
     * Drawer width for a viewport [availableWidth] px wide. Bounded above by a fraction of the
     * surface so an 800px panel does not get a drawer covering most of the page.
     */
    fun drawerWidth(availableWidth: Int): Float {
        val preferred = dp(DRAWER_WIDTH_DP)
        if (availableWidth <= 0) return preferred
        return preferred.coerceIn(availableWidth * 0.30f, availableWidth * 0.55f)
    }
}
