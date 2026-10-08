package dev.autobridge.car

/**
 * Colours, dimensions and timings for the surface-drawn Android Auto home dashboard.
 *
 * Every value the dashboard paints with lives here so the header, the Now Playing card, the six
 * quick-access cards and the Recently Sent row stay one design system. The numbers are the car
 * home design ("CarHomeNew" on the design canvas), drawn at 1280 × 720; dimensions are in dp and
 * [HomeDashboardLayout] converts them with the head unit's own density, never the phone's, then
 * scales the whole column to the height the host leaves free.
 */
internal object HomeDashboardTheme {
    // --- Surfaces -------------------------------------------------------------------------
    const val BACKGROUND = 0xFF121418.toInt()
    const val CARD = 0xFF1C1F25.toInt()
    const val CARD_FOCUSED = 0xFF242831.toInt()
    const val CARD_PRESSED = 0xFF2A2F38.toInt()

    /** Round control buttons on the Now Playing card, and the scroll buttons. */
    const val CONTROL = 0xFF2C3036.toInt()
    const val CONTROL_PRESSED = 0xFF3A3F47.toInt()

    /** Unfilled part of a progress bar. */
    const val PROGRESS_TRACK = 0xFF2C3036.toInt()

    // --- Text -----------------------------------------------------------------------------
    const val TEXT_PRIMARY = 0xFFF1F3F5.toInt()
    const val TEXT_SECONDARY = 0xFF9AA1AA.toInt()
    /** Section label above the Recently Sent row. */
    const val TEXT_SECTION = 0xFFC7CCD2.toInt()
    /** Glyph on a round control button. */
    const val TEXT_CONTROL = 0xFFE3E6EA.toInt()

    /** The "Bridge" half of the wordmark. */
    const val ACCENT_WORDMARK = 0xFF7FA9F5.toInt()

    /** AutoBridge blue: the play button, progress fill, the queue link and the focus border. */
    const val ACCENT = 0xFFB3C7F5.toInt()
    /** Glyph drawn on an [ACCENT] fill. */
    const val ON_ACCENT = 0xFF142540.toInt()

    // --- Feature accents (icon glyph colour; the tile behind it is a dark tint of the same) ---
    const val ACCENT_TV = 0xFF7FA9F5.toInt()
    const val ACCENT_RADIO = 0xFFF2A66E.toInt()
    const val ACCENT_WEB = 0xFFA99BF2.toInt()
    const val ACCENT_YOUTUBE = 0xFFF0848F.toInt()
    const val ACCENT_YOUTUBE_MUSIC = 0xFFF28BB0.toInt()
    const val ACCENT_STREAMING = 0xFFE8C27A.toInt()

    /** Share of the accent mixed into [CARD] for an icon tile's fill and its hairline border. */
    const val ICON_TILE_TINT = 0.08f
    const val ICON_TILE_BORDER_TINT = 0.30f

    /** Share of the accent mixed into [CARD] for fallback artwork, top and bottom of its ramp. */
    const val ARTWORK_TINT_TOP = 0.26f
    const val ARTWORK_TINT_BOTTOM = 0.08f

    // --- Dimensions (dp, at scale 1) --------------------------------------------------------
    object Dp {
        const val TOP_PADDING = 20f
        const val BOTTOM_PADDING = 16f
        const val MARGIN = 28f

        // Header
        const val LOGO = 54f
        const val HEADER = 56f
        const val TITLE = 28f
        const val LOGO_TO_TITLE = 14f

        /** Between every section, and between the cards of the grid. */
        const val GAP = 18f

        // Now Playing card
        const val HERO_WIDTH = 400f
        const val HERO_PADDING = 18f
        const val HERO_ART = 84f
        const val HERO_ART_RADIUS = 16f
        const val HERO_CAPTION = 15f
        const val HERO_TITLE = 21f
        const val HERO_PROGRESS = 6f
        const val HERO_TIME = 14f
        const val HERO_INNER_GAP = 14f
        const val CONTROL = 72f
        const val CONTROL_PRIMARY = 84f
        const val CONTROL_GLYPH = 28f
        const val CONTROL_PRIMARY_GLYPH = 34f

        // Quick-access cards
        const val CARD_HEIGHT = 128f
        /** The design's card width, which decides how far a head unit may scale the column. */
        const val CARD_WIDTH = 224f
        const val CARD_RADIUS = 22f
        const val CARD_PADDING = 20f
        const val ICON = 56f
        const val ICON_RADIUS = 16f
        const val ICON_LABEL_GAP = 12f
        const val LABEL = 21f
        const val LABEL_MIN = 15f

        /** Narrower than this and a card's label stops being readable: the hero stacks above. */
        const val CARD_MIN_WIDTH = 150f

        // Recently Sent
        const val SECTION_TITLE = 18f
        const val SECTION_LINK = 16f
        const val SECTION_TITLE_GAP = 12f
        const val ROW_HEIGHT = 76f
        const val ROW_RADIUS = 18f
        const val ROW_PADDING_START = 12f
        const val ROW_PADDING_END = 16f
        const val ROW_ICON = 48f
        const val ROW_ICON_RADIUS = 14f
        const val ROW_TITLE = 17f
        const val ROW_META = 14f
        /** Narrower than this and the rows stack instead of sitting three across. */
        const val ROW_MIN_WIDTH = 220f

        const val FOCUS_BORDER = 2f
        const val FOCUS_GLOW = 5f

        const val SCROLL_BUTTON = 56f
        const val SCROLL_COLUMN_GAP = 14f

        /** Widest the column gets; an ultra-wide head unit centres it instead of stretching it. */
        const val MAX_CONTENT_WIDTH = 1400f
    }

    /**
     * How far the whole column may scale to fill the free height. Below [MIN_SCALE] the touch
     * targets get too small and the column scrolls instead; above [MAX_SCALE] a large screen gets
     * breathing room rather than giant cards.
     */
    const val MIN_SCALE = 0.8f
    const val MAX_SCALE = 1.35f

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
