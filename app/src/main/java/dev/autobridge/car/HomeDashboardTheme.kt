package dev.autobridge.car

import dev.autobridge.design.DesignTokens

/**
 * Colours, dimensions and timings for the surface-drawn Android Auto home dashboard.
 *
 * The home itself now paints with [HomeDashboardLook], resolved from Settings > Appearance; the
 * colour constants here are what the car's other surface screens (the radio tuner) and the
 * features' own hues still read. Dimensions and timings are shared by all of them.
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
    const val BACKGROUND = DesignTokens.CarHome.BACKGROUND
    const val CARD = DesignTokens.CarHome.CARD
    const val CARD_FOCUSED = DesignTokens.CarHome.CARD_FOCUSED
    const val CARD_PRESSED = DesignTokens.CarHome.CARD_PRESSED

    /** Round control buttons on the Now Playing card, and the scroll buttons. */
    const val CONTROL = DesignTokens.CarHome.CONTROL
    const val CONTROL_PRESSED = DesignTokens.CarHome.CONTROL_PRESSED

    /** The queue button, and its label and chevron. */
    const val QUEUE_PILL = DesignTokens.CarHome.QUEUE_PILL
    const val QUEUE_PILL_PRESSED = DesignTokens.CarHome.QUEUE_PILL_PRESSED
    const val QUEUE_PILL_TEXT = DesignTokens.CarHome.QUEUE_PILL_TEXT
    const val QUEUE_PILL_CHEVRON = DesignTokens.CarHome.QUEUE_PILL_CHEVRON

    /** Dashed outline of an empty state: no session yet, nothing sent, an empty queue. */
    const val EMPTY_OUTLINE = DesignTokens.CarHome.EMPTY_OUTLINE
    const val EMPTY_PILL_OUTLINE = DesignTokens.CarHome.CONTROL_PRESSED
    const val TEXT_DISABLED = DesignTokens.CarHome.TEXT_DISABLED
    /** A control that has nothing to act on (Next with an empty queue). */
    const val CONTROL_DISABLED_GLYPH = DesignTokens.CarHome.CONTROL_DISABLED_GLYPH

    /** Unfilled part of a progress bar. */
    const val PROGRESS_TRACK = DesignTokens.CarHome.CONTROL

    // --- Text -----------------------------------------------------------------------------
    const val TEXT_PRIMARY = DesignTokens.CarHome.TEXT_PRIMARY
    const val TEXT_SECONDARY = DesignTokens.CarHome.TEXT_SECONDARY
    /** Section label above the Recently Sent row. */
    const val TEXT_SECTION = DesignTokens.CarHome.TEXT_SECTION
    /** Glyph on a round control button. */
    const val TEXT_CONTROL = DesignTokens.CarHome.TEXT_CONTROL

    /** The "Bridge" half of the wordmark. */
    const val ACCENT_WORDMARK = DesignTokens.CarHome.ACCENT_WORDMARK

    /** AutoBridge blue: the play button, progress fill, the queue link and the focus border. */
    const val ACCENT = DesignTokens.CarHome.ACCENT
    /** Glyph drawn on an [ACCENT] fill. */
    const val ON_ACCENT = DesignTokens.CarHome.ON_ACCENT

    // --- Feature accents (icon glyph colour; the tile behind it is a dark tint of the same) ---
    const val ACCENT_TV = DesignTokens.CarHome.ACCENT_WORDMARK
    // The radio design's amber, the same as AutoBridgeDesign.ACCENT_RADIO on the phone.
    const val ACCENT_RADIO = DesignTokens.Phone.ACCENT_RADIO

    /**
     * Electric blue for the radio tuner's active control (play button, selected preset), equal to
     * [dev.autobridge.ui.AutoBridgeDesign.ACCENT] so the phone and the car light the same control
     * the same colour. [ACCENT_RADIO] stays the radio section's amber identity badge; this is the
     * "it is on" colour, never the periwinkle [ACCENT] the home's own Now Playing card uses.
     */
    const val ACCENT_RADIO_ACTIVE = DesignTokens.Phone.ACCENT
    /** Glyph drawn on an [ACCENT_RADIO_ACTIVE] fill. */
    const val ON_ACCENT_RADIO_ACTIVE = DesignTokens.CarHome.ON_ACCENT_RADIO_ACTIVE
    const val ACCENT_WEB = DesignTokens.CarHome.ACCENT_WEB
    const val ACCENT_YOUTUBE = DesignTokens.CarHome.ACCENT_YOUTUBE
    const val ACCENT_YOUTUBE_MUSIC = DesignTokens.CarHome.ACCENT_YOUTUBE_MUSIC
    const val ACCENT_STREAMING = DesignTokens.CarHome.ACCENT_STREAMING
    /** The sideload builds' extra home cards (CarExtensions). */
    const val ACCENT_MULTI_APP = DesignTokens.CarHome.ACCENT_MULTI_APP
    const val ACCENT_DESKTOP = DesignTokens.CarHome.ACCENT_DESKTOP

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

        // Classic grid (Appearance > Home layout): tiles fill two rows under the header.
        /** Shorter tiles than this and the grid scrolls instead. */
        const val CLASSIC_TILE_MIN = 120f
        const val CLASSIC_CHIP = 72f
        const val CLASSIC_CHIP_MIN = 44f
        const val CLASSIC_LABEL = 20f
        const val CLASSIC_LABEL_GAP = 14f
        const val CLASSIC_PADDING = 16f

        // Compact (Appearance > Home layout): two columns of rows and a now-playing strip.
        const val COMPACT_ROW_MIN = 64f
        /** Rows stop growing here; the space left sits between them and the strip. */
        const val COMPACT_ROW_MAX = 112f
        const val COMPACT_ROW_GAP = 10f
        const val COMPACT_COLUMN_GAP = 16f
        const val COMPACT_ROW_PADDING = 18f
        /** Between the chip, the name and the chevron. */
        const val COMPACT_ROW_GAP_INNER = 16f
        const val COMPACT_CHIP = 48f
        const val COMPACT_CHEVRON = 22f
        const val COMPACT_TITLE = 20f
        const val COMPACT_META = 17f
        const val COMPACT_STRIP = 76f
        const val COMPACT_STRIP_GAP = 14f
        const val COMPACT_STRIP_ART = 52f
        const val COMPACT_STRIP_PLAY = 60f

        /** Widest the column gets; an ultra-wide head unit centres it instead of stretching it. */
        const val MAX_CONTENT_WIDTH = 1400f
    }

    /**
     * The sizes that change between the two ways the home is laid out.
     *
     * [ROOMY] is the design as drawn for a 1280 × 720 unit (canvas "Home v2 · ทาง A"): a 420dp top
     * row, big cards and controls, the AutoBridge header. A short head unit cannot hold that without
     * scrolling, so it gets [COMPACT] instead: the earlier CarHomeNew proportions (274dp top row,
     * 128dp cards), a narrower Now Playing card, tighter edges and a smaller header (32dp logo,
     * 20dp wordmark). On an 800 × 400 unit that fits whole; where the host also takes a top band
     * and a dock (the DHU's 800 × 480 profile leaves 776 × 300) the header, the card and the whole
     * grid still fit and the sent section sits below the fold. A narrower card is what keeps the
     * grid beside it rather than stacking under it.
     */
    data class Profile(
        /** The AutoBridge header row: its height, the logo in it, the wordmark, and the gap below. */
        val header: Float,
        val logo: Float,
        val title: Float,
        val headerGap: Float,
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
        header = 56f, logo = 54f, title = 28f, headerGap = 18f, topPadding = 20f, bottomPadding = 16f, heroWidth = 400f,
        cardHeight = 201f, cardPadding = 22f, icon = 64f, iconRadius = 18f,
        iconLabelGap = 14f, label = 22f, heroStackedHeight = 310f, heroPadding = 20f, heroArt = 108f,
        heroTitle = 23f, heroInnerGap = 16f, control = 80f, controlPrimary = 96f
    )

    val COMPACT = Profile(
        header = 36f, logo = 32f, title = 20f, headerGap = 10f, topPadding = 8f, bottomPadding = 8f, heroWidth = 300f,
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
