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
