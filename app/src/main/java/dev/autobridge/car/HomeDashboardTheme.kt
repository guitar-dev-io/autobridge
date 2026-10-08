package dev.autobridge.car

/**
 * Colours, dimensions and timings for the surface-drawn Android Auto home dashboard.
 *
 * Every value the dashboard paints with lives here so the header, the Now Playing card, the six
 * quick-access cards and the Recently Sent row stay one design system. The numbers are the car
 * home design ("Home v2 · ทาง A" on the design canvas, CarHomeV2Space and its two state boards),
 * drawn at 1280 × 720; dimensions are in dp and
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

    /** The queue button, and its label and chevron. */
    const val QUEUE_PILL = 0xFF23304A.toInt()
    const val QUEUE_PILL_PRESSED = 0xFF2E3E5F.toInt()
    const val QUEUE_PILL_TEXT = 0xFFC7D3EC.toInt()
    const val QUEUE_PILL_CHEVRON = 0xFF8FA6D6.toInt()

    /** Dashed outline of an empty state: no session yet, nothing sent, an empty queue. */
    const val EMPTY_OUTLINE = 0xFF2F343C.toInt()
    const val EMPTY_PILL_OUTLINE = 0xFF3A3F47.toInt()
    const val TEXT_DISABLED = 0xFF7A8089.toInt()
    /** A control that has nothing to act on (Next with an empty queue). */
    const val CONTROL_DISABLED_GLYPH = 0xFF5E646C.toInt()

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
        const val MARGIN = 28f

        // Header
        const val LOGO = 54f
        const val HEADER = 56f
        const val TITLE = 28f
        const val LOGO_TO_TITLE = 14f

        /** Between every section, and between the cards of the grid. */
        const val GAP = 18f

        // Now Playing card
        const val HERO_ART_RADIUS = 16f
        const val HERO_CAPTION = 15f
        const val HERO_PROGRESS = 6f
        const val HERO_TIME = 14f
        /** Between the progress times and the control row. */
        const val HERO_CONTROLS_GAP = 14f
        const val CONTROL_GLYPH = 28f
        const val CONTROL_PRIMARY_GLYPH = 34f

        // Quick-access cards
        /** The design's card width, which decides how far a head unit may scale the column. */
        const val CARD_WIDTH = 224f
        const val CARD_RADIUS = 22f
        const val LABEL_MIN = 15f

        /** Narrower than this and a card's label stops being readable: the hero stacks above. */
        const val CARD_MIN_WIDTH = 150f

        // Recently Sent
        /** The line holding the section title and the queue button: the button's own height. */
        const val SECTION_HEADER = 48f
        const val SECTION_TITLE = 18f
        const val SECTION_CHEVRON = 20f
        const val SECTION_CHEVRON_GAP = 6f
        const val SECTION_LINK = 16f
        const val QUEUE_PILL_PADDING_START = 18f
        const val QUEUE_PILL_PADDING_END = 14f
        const val QUEUE_PILL_ICON = 22f
        const val QUEUE_PILL_CHEVRON = 18f
        const val QUEUE_PILL_GAP = 10f
        const val SECTION_TITLE_GAP = 12f
        const val ROW_HEIGHT = 76f
        const val ROW_RADIUS = 18f
        const val ROW_PADDING_START = 12f
        const val ROW_PADDING_END = 16f
        const val ROW_ICON = 48f
        const val ROW_ICON_RADIUS = 13f
        const val ROW_TEXT_GAP = 14f
        const val ROW_TITLE = 17f
        const val ROW_META = 14f
        /** Narrower than this and the rows stack instead of sitting three across. */
        const val ROW_MIN_WIDTH = 220f

        // Empty states
        const val EMPTY_BORDER = 2f
        const val EMPTY_DASH = 8f
        const val EMPTY_PADDING = 28f
        const val EMPTY_ICON = 72f
        const val EMPTY_ICON_RADIUS = 20f
        const val EMPTY_TITLE = 24f
        const val EMPTY_HINT = 17f
        const val EMPTY_GAP = 18f

        const val FOCUS_BORDER = 2f
        const val FOCUS_GLOW = 5f

        const val SCROLL_BUTTON = 56f
        const val SCROLL_COLUMN_GAP = 14f

        /** Widest the column gets; an ultra-wide head unit centres it instead of stretching it. */
        const val MAX_CONTENT_WIDTH = 1400f
    }

    /**
     * The sizes that change between the two ways the home is laid out.
     *
     * [ROOMY] is the design as drawn for a 1280 × 720 unit (canvas "Home v2 · ทาง A"): a 420dp top
     * row, big cards and controls, the AutoBridge header. A short head unit cannot hold that without
     * scrolling, so it gets [COMPACT] instead: the earlier CarHomeNew proportions (274dp top row,
     * 128dp cards), a narrower Now Playing card, tighter edges and no header. On an 800 × 400 unit
     * that fits whole; where the host also takes a top band and a dock (the DHU's 800 × 480 profile
     * leaves 776 × 300) the card and the whole grid still fit and only the sent row sits below the
     * fold. The host already names the app, so the wordmark is the first thing to go, and a narrower
     * card is what keeps the grid beside it rather than stacking under it.
     */
    data class Profile(
        val showHeader: Boolean,
        val topPadding: Float,
        val bottomPadding: Float,
        val heroWidth: Float,
        val cardHeight: Float,
        val cardPadding: Float,
        val icon: Float,
        val iconRadius: Float,
        val iconLabelGap: Float,
        val label: Float,
        /** The Now Playing card's height when it sits above the grid instead of beside it. */
        val heroStackedHeight: Float,
        val heroPadding: Float,
        val heroArt: Float,
        val heroTitle: Float,
        val heroInnerGap: Float,
        val control: Float,
        val controlPrimary: Float
    )

    val ROOMY = Profile(
        showHeader = true, topPadding = 20f, bottomPadding = 16f, heroWidth = 400f,
        cardHeight = 201f, cardPadding = 22f, icon = 64f, iconRadius = 18f,
        iconLabelGap = 14f, label = 22f, heroStackedHeight = 310f, heroPadding = 20f, heroArt = 108f,
        heroTitle = 23f, heroInnerGap = 16f, control = 80f, controlPrimary = 96f
    )

    val COMPACT = Profile(
        showHeader = false, topPadding = 8f, bottomPadding = 8f, heroWidth = 300f,
        cardHeight = 128f, cardPadding = 20f, icon = 56f, iconRadius = 16f,
        iconLabelGap = 12f, label = 21f, heroStackedHeight = 274f, heroPadding = 18f, heroArt = 84f,
        heroTitle = 21f, heroInnerGap = 14f, control = 72f, controlPrimary = 84f
    )

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
