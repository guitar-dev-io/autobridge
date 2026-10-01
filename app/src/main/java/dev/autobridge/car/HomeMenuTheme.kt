package dev.autobridge.car

/**
 * Colours, dimensions and timings for the surface-drawn Android Auto home menu.
 *
 * Every value the menu paints with lives here so the six cards, the header and the scroll
 * buttons stay one design system. Dimensions are in dp; [HomeMenuLayout] converts them with the
 * head unit's own density, never the phone's.
 */
internal object HomeMenuTheme {
    // --- Surfaces -------------------------------------------------------------------------
    const val BACKGROUND = 0xFF171A1F.toInt()
    const val CARD = 0xFF20242A.toInt()
    const val CARD_FOCUSED = 0xFF262B32.toInt()
    const val CARD_PRESSED = 0xFF2C323A.toInt()
    const val BORDER = 0xFF343A43.toInt()

    // --- Text -----------------------------------------------------------------------------
    const val TEXT_PRIMARY = 0xFFF3F5F7.toInt()
    const val TEXT_SECONDARY = 0xFFAEB4BC.toInt()

    /** AutoBridge blue: focus border, the "Bridge" half of the wordmark. */
    const val ACCENT = 0xFF4DA3FF.toInt()

    // --- Feature accents (icon glyph colour; the tile behind it is a dark tint of the same) ---
    const val ACCENT_TV = 0xFF4FC3F7.toInt()
    const val ACCENT_RADIO = 0xFFFFA94D.toInt()
    const val ACCENT_WEB = 0xFF8F8CFF.toInt()
    const val ACCENT_YOUTUBE = 0xFFFF4E45.toInt()
    const val ACCENT_YOUTUBE_MUSIC = 0xFFFF3D6E.toInt()
    const val ACCENT_STREAMING = 0xFFFFD43B.toInt()

    /** Share of the accent mixed into [CARD] for an icon tile's fill and its hairline border. */
    const val ICON_TILE_TINT = 0.16f
    const val ICON_TILE_BORDER_TINT = 0.32f

    // --- Dimensions (dp) --------------------------------------------------------------------
    object Dp {
        const val LOGO = 54f
        const val LOGO_COMPACT = 44f
        const val TITLE = 30f
        const val TITLE_COMPACT = 26f
        const val LOGO_TO_TITLE = 14f

        const val TOP_PADDING = 12f
        const val TOP_PADDING_COMPACT = 8f
        const val BOTTOM_PADDING = 12f
        const val MARGIN_MIN = 24f
        const val MARGIN_MAX = 32f
        const val HEADER_TO_GRID = 20f
        const val HEADER_TO_GRID_COMPACT = 14f
        const val GAP = 16f
        const val GAP_COMPACT = 12f

        const val CARD_RADIUS = 22f
        const val CARD_BORDER = 1.25f
        /** Below this the compact header is tried, then scrolling. Still a large touch target. */
        const val CARD_MIN_HEIGHT = 96f
        const val CARD_MAX_HEIGHT = 210f
        const val CARD_PADDING = 10f

        const val ICON_MIN = 40f
        const val ICON_MAX = 72f
        const val ICON_LABEL_GAP_MIN = 10f
        const val ICON_LABEL_GAP_MAX = 14f

        const val LABEL_MAX = 22f
        const val LABEL_MIN = 16f

        const val FOCUS_BORDER = 2f
        const val FOCUS_GLOW = 5f

        const val SCROLL_BUTTON = 56f
        const val SCROLL_COLUMN_GAP = 14f
    }

    /** Icon tile corner radius as a share of the tile side (≈18dp on a 72dp tile). */
    const val ICON_RADIUS_RATIO = 0.25f

    /** Widest a card may get relative to its height, so ultra-wide head units centre the grid. */
    const val CARD_MAX_ASPECT = 2.0f

    /** Pressed flash shown before navigating. Kept inside the 100–180ms budget. */
    const val PRESS_FEEDBACK_MS = 140L

    /** Linear mix of two opaque ARGB colours; [t] = 0 returns [from]. */
    fun mix(from: Int, to: Int, t: Float): Int {
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * t).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** Same colour with a new alpha (0–1). */
    fun withAlpha(color: Int, alpha: Float): Int =
        ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24) or (color and 0x00FFFFFF)
}
