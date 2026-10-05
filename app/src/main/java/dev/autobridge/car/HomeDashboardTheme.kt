package dev.autobridge.car

/**
 * Colours, dimensions and timings for the surface-drawn Android Auto home dashboard.
 *
 * Every value the dashboard paints with lives here so the header, the Continue Watching card, the
 * six quick-access cards and the Recently Sent / Queue blocks stay one design system. Dimensions
 * are in dp; [HomeDashboardLayout] converts them with the head unit's own density, never the
 * phone's.
 */
internal object HomeDashboardTheme {
    // --- Surfaces -------------------------------------------------------------------------
    const val BACKGROUND = 0xFF171A1F.toInt()
    const val CARD = 0xFF20242A.toInt()
    const val CARD_FOCUSED = 0xFF262B32.toInt()
    const val CARD_PRESSED = 0xFF2C323A.toInt()
    const val BORDER = 0xFF343A43.toInt()

    /** Container behind a list block (Recently Sent, Queue); one step below [CARD]. */
    const val SURFACE_SUNKEN = 0xFF1B1F25.toInt()

    /** A row inside a block, and its pressed state. */
    const val ROW = 0xFF20242A.toInt()
    const val ROW_PRESSED = 0xFF2C323A.toInt()

    /** Unfilled part of a progress bar. */
    const val PROGRESS_TRACK = 0xFF343A43.toInt()

    // --- Text -----------------------------------------------------------------------------
    const val TEXT_PRIMARY = 0xFFF3F5F7.toInt()
    const val TEXT_SECONDARY = 0xFFAEB4BC.toInt()

    /** AutoBridge blue: focus border, the "Bridge" half of the wordmark, progress fill. */
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

    /** Share of the accent mixed into [CARD] for fallback artwork, top and bottom of its ramp. */
    const val ARTWORK_TINT_TOP = 0.26f
    const val ARTWORK_TINT_BOTTOM = 0.08f

    /**
     * The vertical budget, as a share of the height the host leaves free.
     *
     * The dashboard is laid out by dividing that height, not by stacking whatever each piece
     * happens to measure: the quick-access grid is the thing a driver reaches for, so it gets
     * roughly half the screen on every head unit, and nothing above it may grow at its expense.
     * [HomeDashboardLayout] clamps each band into a dp range afterwards, so a very small screen
     * keeps its touch targets and scrolls instead of honouring the percentages into unusability.
     *
     * A band includes the gap that follows it. [BLOCK_HEADER] budgets only the Recently Sent /
     * Queue *header*: their rows live in what is left over, and below the fold, which is what
     * makes the section a peek rather than a third of the screen.
     *
     * The shares deliberately leave [BREATHING] unspent at the bottom. When a section has no
     * content its share is dropped and the rest are scaled up to fill the same total, so an empty
     * dashboard gives the space to the cards rather than to a hole.
     */
    object Budget {
        const val HEADER = 0.13f
        const val CONTINUE_WATCHING = 0.21f
        const val QUICK_ACCESS_TITLE = 0.06f
        const val QUICK_ACCESS_GRID = 0.46f
        const val BLOCK_HEADER = 0.08f

        /** What the five bands leave for the bottom edge; not a band, just what is not spent. */
        const val BREATHING = 1f - (HEADER + CONTINUE_WATCHING + QUICK_ACCESS_TITLE + QUICK_ACCESS_GRID + BLOCK_HEADER)

        /** Everything the bands may spend between them, which is what absent sections scale into. */
        const val SPENDABLE = 1f - BREATHING
    }

    // --- Dimensions (dp) --------------------------------------------------------------------
    object Dp {
        const val LOGO = 54f
        const val LOGO_COMPACT = 44f
        /** The logo grows with the header band on a roomy head unit, up to here. */
        const val LOGO_MAX = 88f
        const val TITLE = 30f
        const val TITLE_COMPACT = 26f
        const val LOGO_TO_TITLE = 14f

        const val TOP_PADDING = 4f
        const val TOP_PADDING_COMPACT = 4f
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
        const val CARD_MAX_HEIGHT = 180f
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

        // --- Band floors and ceilings ---------------------------------------------------
        // What each budget band may shrink or grow to once its percentage is worked out. The
        // floors are what keeps a small head unit touchable; past them the column scrolls.
        // Each floor is what that band's contents measure at their smallest, plus the gap the
        // band carries after it; each ceiling is the same sum at their largest.
        // Floors are what the band's contents measure at their smallest plus the gap it carries;
        // ceilings are set so the budget's percentages are reachable on the head units this runs
        // on, and only bite on a very large screen, where the unspent height becomes breathing
        // room rather than a header the size of a card.
        const val HEADER_BAND_MIN = LOGO_COMPACT + GAP_COMPACT
        const val HEADER_BAND_MAX = 120f
        const val HERO_BAND_MIN = 140f
        const val HERO_BAND_MAX = 216f
        const val QUICK_TITLE_BAND_MIN = 30f
        const val QUICK_TITLE_BAND_MAX = 52f
        const val GRID_BAND_MIN = 2f * CARD_MIN_HEIGHT + 2f * GAP_COMPACT
        const val GRID_BAND_MAX = 2f * CARD_MAX_HEIGHT + 2f * GAP
        const val BLOCK_PEEK_MIN = 34f
        const val BLOCK_PEEK_MAX = 64f

        // --- Dashboard sections ---------------------------------------------------------
        /** "Continue Watching", "Quick Access": the label above a section. */
        const val SECTION_TITLE = 21f
        const val SECTION_TITLE_MIN = 17f
        const val SECTION_TITLE_GAP = 10f
        /** Vertical space between one section's last element and the next section's label. */
        const val SECTION_GAP = 18f
        const val SECTION_GAP_COMPACT = 12f

        // Continue Watching
        const val HERO_MIN_HEIGHT = 96f
        const val HERO_MAX_HEIGHT = 168f
        const val HERO_PADDING = 12f
        const val HERO_TITLE_MAX = 27f
        const val HERO_TITLE_MIN = 20f
        const val HERO_META = 18f
        const val HERO_META_MIN = 15f
        const val HERO_PROGRESS = 6f
        const val HERO_LINE_GAP = 8f
        /** Centre badge over the thumbnail. */
        const val PLAY_BADGE = 46f
        const val PLAY_BADGE_MIN = 32f

        // Recently Sent / Queue blocks
        const val BLOCK_RADIUS = 20f
        const val BLOCK_PADDING = 12f
        const val BLOCK_HEADER = 20f
        const val BLOCK_HEADER_MIN = 16f
        const val BLOCK_HEADER_GAP = 10f
        const val ROW_MIN_HEIGHT = 54f
        const val ROW_MAX_HEIGHT = 78f
        const val ROW_RADIUS = 14f
        const val ROW_GAP = 8f
        const val ROW_PADDING = 8f
        const val ROW_TITLE = 19f
        const val ROW_TITLE_MIN = 16f
        const val ROW_META = 15f
        const val ROW_META_MIN = 13f
        const val ROW_PLAY_BADGE = 36f
        const val CHEVRON = 15f
        const val COUNT_PILL_PADDING = 8f

        /** Thumbnail corner radius, used by the hero and by every row. */
        const val THUMB_RADIUS = 10f
    }

    /** Wordmark text size as a share of the logo tile, from the original 54dp / 30dp pair. */
    const val WORDMARK_RATIO = 30f / 54f

    /** Icon tile corner radius as a share of the tile side (≈18dp on a 72dp tile). */
    const val ICON_RADIUS_RATIO = 0.25f

    /** Widest a card may get relative to its height, so ultra-wide head units centre the grid. */
    const val CARD_MAX_ASPECT = 2.0f

    /** Thumbnails are drawn at this aspect, which is what every source here actually is. */
    const val THUMB_ASPECT = 16f / 9f

    /**
     * Narrower than this (in dp) and Recently Sent / Queue stack instead of sitting side by side.
     * Two blocks plus their gap need roughly 380dp each before a title stops being readable.
     */
    const val SIDE_BY_SIDE_MIN_WIDTH = 860f

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
