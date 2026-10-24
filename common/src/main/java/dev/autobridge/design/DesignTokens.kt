package dev.autobridge.design

/**
 * Every colour AutoBridge draws with, in one place: the phone app, the car home, the browsers, the
 * keyboard, multi-app, the desktop browser rail and the radio design. Each surface's own theme
 * object (AutoBridgeDesign, HomeDashboardTheme, BrowserTheme, BrowserKeyboardStyle, ...) keeps its
 * names, and those names read their values from here, so a colour is changed once and the design
 * page (design/index.html, generated from this file by scripts/design-page.py) shows it.
 *
 * A colour that is already one of the core [Phone] palette's, or already in its own group, is that
 * token rather than a copy. Brand colours (a streaming service's logo) are not design tokens and
 * stay with the logo.
 *
 * Lives in :common so :app, :duoscreen and :desktopbrowser all read the same values.
 */
object DesignTokens {

    /** Phone app: pages, cards, text, accents (AutoBridgeDesign). */
    object Phone {
        const val INK = 0xFF12151A.toInt()
        const val SURFACE = 0xFF1C2027.toInt()
        const val SURFACE_RAISED = 0xFF232831.toInt()
        const val HAIRLINE = 0xFF2E343E.toInt()
        const val TEXT = 0xFFF3F5F7.toInt()
        const val TEXT_MUTED = 0xFFA9B0BA.toInt()
        const val ACCENT = 0xFF4DA3FF.toInt()
        const val ACCENT_SOFT = 0xFF33C9D6.toInt()
        const val DANGER = 0xFFFF9AA8.toInt()
        const val ACCENT_ONLINE = 0xFF5BE3B4.toInt()
        const val SIGNAL_GOOD = 0xFF5BD98A.toInt()
        const val SIGNAL_SLOW = 0xFFFFC65C.toInt()
        const val ACCENT_TV = 0xFF6EA8FF.toInt()
        const val ACCENT_RADIO = 0xFFFFB35C.toInt()
        const val ACCENT_WEB = 0xFF7DD3C0.toInt()
        const val ACCENT_VIDEO = 0xFFFF7A8A.toInt()
        const val ACCENT_FILES = 0xFF9BE08A.toInt()
        const val ACCENT_FAVORITE = 0xFFFF8FB1.toInt()
        const val ACCENT_SYSTEM = 0xFFB39DFF.toInt()
        const val ACCENT_DARK = 0xFF0B5D96.toInt()
        const val SUCCESS = 0xFF2EE879.toInt()
        const val WARNING = 0xFFFFBF5F.toInt()
        const val WEATHER = 0xFF6BC5FF.toInt()
        /** Recent chips on Home. */
        const val CHIP_TEXT = 0xFFD5DAE0.toInt()
        /** The Duo button on Home, the selected segment on Control. */
        const val SELECTED_FILL = 0xFF22344F.toInt()
        const val ON_SELECTED_FILL = 0xFFDCEBFF.toInt()
        /** Control: the card that leads back to Home. */
        const val HOME_CARD = 0xFF16243A.toInt()
        const val HOME_CARD_ICON_CHIP = 0xFF1E3A66.toInt()
        const val HOME_CARD_CAPTION = 0xFFA9C3E3.toInt()
        /** Destructive buttons in the phone shell. */
        const val DESTRUCTIVE = 0xFFD93838.toInt()
        /** The entertainment page header and caption. */
        const val ENTERTAINMENT_BACKGROUND = 0xFF101319.toInt()
        const val ENTERTAINMENT_CAPTION = 0xFFA0B2CD.toInt()
    }

    /** Radio: the warm amber design shared by the phone and the car. */
    object Radio {
        const val ON_AMBER = 0xFF2A1A05.toInt()
        const val WARM_CARD = 0xFF2A2116.toInt()
        const val WARM_EDGE = 0xFF5A4022.toInt()
        const val WARM_TEXT = 0xFFFFC985.toInt()
        const val SOFT_TEXT = 0xFFC9CDD3.toInt()
        const val BACKGROUND = 0xFF0B0D10.toInt()
        const val CONTROL_PRESSED = 0xFF343A44.toInt()
        const val LIVE_RED = 0xFFFF5C5C.toInt()
        const val TEXT_CONTROL = 0xFFE3E6EA.toInt()
        const val TEXT_HINT = 0xFFA9AFB8.toInt()
        const val TEXT_DISABLED = 0xFF5A616B.toInt()
        const val CHIP_PRESSED = 0xFF2A3038.toInt()
        const val ACTION_CARD = 0xFF262A31.toInt()
        const val LOGO_CARD = 0xFFF4F5F7.toInt()
        const val ACCENT_PRESSED = 0xFFE89E48.toInt()
        const val SCROLL = 0x66F3F5F7
        const val SHEET = 0xFF1A1E25.toInt()
        const val HANDLE = 0xFF3A404A.toInt()
        const val AMBER_TRACK = 0x80FFB35C.toInt()
        const val TAB_BAR = 0xFF0E1014.toInt()
        const val TAB_EDGE = 0xFF22262E.toInt()
        const val TAB_PILL = 0xFF3A2C1A.toInt()
        const val HINT_TEXT = 0xFF8A919B.toInt()

        /** Placeholder station tiles: a station without a logo gets one of these by name. */
        val TILE_PALETTE = intArrayOf(
            0xFFE0683C.toInt(), 0xFFD9475F.toInt(), 0xFF9A55D6.toInt(), 0xFF4F6BE0.toInt(),
            0xFF2B8FD6.toInt(), 0xFF199E8E.toInt(), 0xFF3C9E4F.toInt(), 0xFFC2852A.toInt(),
        )
    }

    /** Car home dashboard (HomeDashboardTheme). */
    object CarHome {
        const val BACKGROUND = 0xFF121418.toInt()
        const val CARD = 0xFF1C1F25.toInt()
        const val CARD_FOCUSED = 0xFF242831.toInt()
        const val CARD_PRESSED = 0xFF2A2F38.toInt()
        const val CONTROL = 0xFF2C3036.toInt()
        const val CONTROL_PRESSED = 0xFF3A3F47.toInt()
        const val QUEUE_PILL = 0xFF23304A.toInt()
        const val QUEUE_PILL_PRESSED = 0xFF2E3E5F.toInt()
        const val QUEUE_PILL_TEXT = 0xFFC7D3EC.toInt()
        const val QUEUE_PILL_CHEVRON = 0xFF8FA6D6.toInt()
        const val EMPTY_OUTLINE = 0xFF2F343C.toInt()
        const val TEXT_DISABLED = 0xFF7A8089.toInt()
        const val CONTROL_DISABLED_GLYPH = 0xFF5E646C.toInt()
        const val TEXT_PRIMARY = 0xFFF1F3F5.toInt()
        const val TEXT_SECONDARY = 0xFF9AA1AA.toInt()
        const val TEXT_SECTION = 0xFFC7CCD2.toInt()
        const val TEXT_CONTROL = 0xFFE3E6EA.toInt()
        const val ACCENT_WORDMARK = 0xFF7FA9F5.toInt()
        const val ACCENT = 0xFFB3C7F5.toInt()
        const val ON_ACCENT = 0xFF142540.toInt()
        const val ON_ACCENT_RADIO_ACTIVE = 0xFF08203A.toInt()
        const val ACCENT_WEB = 0xFFA99BF2.toInt()
        const val ACCENT_YOUTUBE = 0xFFF0848F.toInt()
        const val ACCENT_YOUTUBE_MUSIC = 0xFFF28BB0.toInt()
        const val ACCENT_STREAMING = 0xFFE8C27A.toInt()
        const val ACCENT_MULTI_APP = 0xFF7FD6C2.toInt()
        const val ACCENT_DESKTOP = 0xFF9DC3F7.toInt()
    }

    /** Glyph art on the car home cards (DashboardArtwork). */
    object DashboardArt {
        /** Compact card fill. */
        const val CARD = 0xFF202228.toInt()
        /** Compact card outline. */
        const val CARD_EDGE = 0xFF50525A.toInt()
        /** Glyph stroke on a compact card. */
        const val GLYPH_COMPACT = 0xFFF5F5F7.toInt()
        const val BROWSER = 0xFF70ACFF.toInt()
        const val MIRROR = 0xFFB56DFF.toInt()
        const val MEDIA = 0xFFAF80F8.toInt()
        /** Agent, gallery. */
        const val TEAL = 0xFF7CE1CB.toInt()
        /** Quick launch, folders, playlists. */
        const val GREEN = 0xFF86E3AF.toInt()
        /** TV, YouTube Music, streaming. */
        const val VIDEO = 0xFFFF7A7A.toInt()
        const val RADIO = 0xFFFFC46B.toInt()
        const val FAVORITES = 0xFFFF8FB1.toInt()
        /** Recent, more, YouTube, settings. */
        const val NEUTRAL = 0xFFD8E3F7.toInt()
        const val WEATHER = 0xFF6BC5FF.toInt()
    }

    /** Car and phone browser chrome, dark scheme (Material 3 roles, BrowserTheme). */
    object BrowserDark {
        const val PRIMARY = 0xFFAAC7FF.toInt()
        const val ON_PRIMARY = 0xFF0A305F.toInt()
        const val PRIMARY_CONTAINER = 0xFF284777.toInt()
        const val ON_PRIMARY_CONTAINER = 0xFFD6E3FF.toInt()
        const val SECONDARY_CONTAINER = 0xFF3E4759.toInt()
        const val ON_SECONDARY_CONTAINER = 0xFFDAE2F9.toInt()
        const val SURFACE = 0xFF111318.toInt()
        const val SURFACE_CONTAINER_LOWEST = 0xFF0C0E13.toInt()
        const val SURFACE_CONTAINER_LOW = 0xFF191C20.toInt()
        const val SURFACE_CONTAINER = 0xFF1D2024.toInt()
        const val SURFACE_CONTAINER_HIGH = 0xFF282A2F.toInt()
        const val SURFACE_CONTAINER_HIGHEST = 0xFF33353A.toInt()
        const val ON_SURFACE = 0xFFE2E2E9.toInt()
        const val ON_SURFACE_VARIANT = 0xFFC4C6D0.toInt()
        const val OUTLINE = 0xFF8E9099.toInt()
        const val OUTLINE_VARIANT = 0xFF44474E.toInt()
        const val ERROR = 0xFFFFB4AB.toInt()
        const val ICON_DISABLED = 0xFF686A6F.toInt()
        const val SECURE_BADGE = 0xFF9CD67D.toInt()
        const val INSECURE_BADGE = 0xFFF3BD6E.toInt()
        const val TILE_DISABLED_BACKGROUND = 0xFF3E4045.toInt()
        const val TILE_ICON_CHIP = 0xFF555F73.toInt()
        /** The phone browser floating button: fill and edge. */
        const val FAB_FILL = 0xE6101113.toInt()
        const val FAB_EDGE = 0xFF526FA6.toInt()
        /** The tab that brings the toolbar back in fullscreen. */
        const val FULLSCREEN_TAB = 0x5A14161A
    }

    /** Phone browser chrome, light scheme (Material 3 roles, BrowserTheme). */
    object BrowserLight {
        const val PRIMARY = 0xFF415F91.toInt()
        const val ON_PRIMARY = 0xFFFFFFFF.toInt()
        const val PRIMARY_CONTAINER = 0xFFD6E3FF.toInt()
        const val ON_PRIMARY_CONTAINER = 0xFF284777.toInt()
        const val SECONDARY_CONTAINER = 0xFFDAE2F9.toInt()
        const val ON_SECONDARY_CONTAINER = 0xFF3E4759.toInt()
        const val SURFACE = 0xFFF9F9FF.toInt()
        const val SURFACE_CONTAINER_LOW = 0xFFF3F3FA.toInt()
        const val SURFACE_CONTAINER = 0xFFEDEDF4.toInt()
        const val SURFACE_CONTAINER_HIGH = 0xFFE7E8EE.toInt()
        const val SURFACE_CONTAINER_HIGHEST = 0xFFE2E2E9.toInt()
        const val ON_SURFACE = 0xFF191C20.toInt()
        const val ON_SURFACE_VARIANT = 0xFF44474E.toInt()
        const val OUTLINE = 0xFF74777F.toInt()
        const val OUTLINE_VARIANT = 0xFFC4C6D0.toInt()
        const val ERROR = 0xFFBA1A1A.toInt()
        const val ICON_DISABLED = 0xFF9C9DA3.toInt()
        const val SECURE_BADGE = 0xFF3F6A32.toInt()
        const val INSECURE_BADGE = 0xFF7A5900.toInt()
        const val TILE_DISABLED_BACKGROUND = 0xFFCECFD5.toInt()
        const val TILE_ICON_CHIP = 0xFFC6CFE6.toInt()
    }

    /** The car browser keyboard. */
    object Keyboard {
        // Material 3 dark tonal roles, the same palette as BrowserDark.
        const val TRAY = 0xFF1D2024.toInt()
        const val TRAY_EDGE = 0xFF44474E.toInt()
        const val LETTER_CAP = 0xFF33353A.toInt()
        const val FUNCTION_CAP = 0xFF3E4759.toInt()
        const val ON_FUNCTION_CAP = 0xFFDAE2F9.toInt()
        const val FIELD_FILL = 0xFF0C0E13.toInt()
        const val CAP_SHADOW = 0xFF05090F.toInt()
        const val SHIFT_ON_CAP = 0xFF284777.toInt()
        const val SHIFT_ON_INK = 0xFFD6E3FF.toInt()
        const val GO_CAP = 0xFFAAC7FF.toInt()
        const val ON_GO_CAP = 0xFF0A305F.toInt()
        const val INK = 0xFFE2E2E9.toInt()
        const val INK_SECONDARY = 0xFFC4C6D0.toInt()
        const val FIELD_EDGE = 0xFF8E9099.toInt()
        const val CURSOR = 0xFFAAC7FF.toInt()
        const val SELECTION = 0x66AAC7FF
        // Pressed = resting cap + 12% of its on-colour (the M3 pressed state layer), per channel, rounded.
        const val LETTER_PRESSED = 0xFF484A4F.toInt()
        const val FUNCTION_PRESSED = 0xFF515A6C.toInt()
        const val GO_PRESSED = 0xFF97B5EC.toInt()
        const val SHIFT_ON_PRESSED = 0xFF3D5A87.toInt()
        const val PRESSED_CAP = LETTER_PRESSED
        const val GO_PRESSED_CAP = GO_PRESSED
    }

    /** Multi-app (Duo Screen) chrome on the car. */
    object MultiApp {
        const val SURFACE = 0xFF0B0D10.toInt()
        const val BUTTON = 0xFF262A31.toInt()
        const val BUTTON_PRESSED = 0xFF343A43.toInt()
        const val ICON = 0xFFD5DAE0.toInt()
        const val GRIP = 0xFF4A5260.toInt()
        const val ACCENT = 0xFFB3C7F5.toInt()
        const val ON_ACCENT = 0xFF142540.toInt()
        const val DIM = 0xB80B0D10.toInt()
        const val CHIP = 0xFF2C3036.toInt()
        const val CHIP_SELECTED = 0xFF16213A.toInt()
        const val CHIP_TEXT_SELECTED = 0xFFDCE6FF.toInt()
        const val CHIP_GLYPH = 0xFF6B7380.toInt()
        const val SWAP = 0xFF2D4470.toInt()
        const val SWAP_TARGET = 0xB82D4470.toInt()
        const val TEXT = 0xFFF1F3F5.toInt()
        const val TEXT_MUTED = 0xFFC7CCD2.toInt()
        const val CARD_BUTTON_TEXT = 0xFF0F1114.toInt()
        const val TILE = 0xFF1C2330.toInt()
        const val TOOLBAR = 0xF21C1F25.toInt()
        const val TOOLBAR_SHADOW = 0x66000000
        const val CARD_EDGE = 0x1FFFFFFF
        const val ACCENT_SOFT = 0x2EB3C7F5
        /** Preset chip glyph: second pane when selected, and the unselected fill. */
        const val CHIP_GLYPH_ON = 0xFFC7D3EC.toInt()
        const val CHIP_GLYPH_FILL = 0xFFC2C8D0.toInt()
        const val CARD_BUTTON_PRESSED = 0xFFC9CED6.toInt()
    }

    /** Desktop browser (Chrome/Brave) control rail and sheets. */
    object DesktopRail {
        const val HANDLE = 0x52F3F5F7
        const val HANDLE_DOT = 0xFF0B0D10.toInt()
        const val PANEL_FILL = 0xF014181E.toInt()
        const val SHEET_FILL = 0xF5171B21.toInt()
        const val PANEL_EDGE = 0xFF2E3641.toInt()
        const val BUTTON_FILL = 0xFF232A33.toInt()
        const val OUTLINED_FILL = 0xFF1B3550.toInt()
        const val SELECTED = 0xFF1E6FD1.toInt()
        const val INK_SECONDARY = 0xFFC9D0D8.toInt()
        const val MUTED = 0xFF8A95A3.toInt()
    }
}
