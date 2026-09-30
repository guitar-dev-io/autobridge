package dev.autobridge.browser

import android.graphics.Color

/**
 * Single source of truth for browser colors, shared by the phone [BrowserActivity] (native Views)
 * and the car [CarWebRenderer] (Canvas-drawn chrome), so both presentations read as one app the way
 * a Fermata-style browser keeps its toolbar/menu look consistent across screens.
 *
 * Sizing deliberately lives in [AutoUiSizes], not here. This object used to also expose a
 * `chromeScale(shortestWidthDp)` that grew icons with the screen's *dimension*, which is what made
 * a wide head unit render toolbar glyphs at tablet size.
 */
object BrowserTheme {
    val background = Color.rgb(20, 22, 26)
    val toolbarBackground = Color.rgb(20, 22, 26)
    val addressPillBackground = Color.rgb(38, 41, 48)
    val iconEnabled = Color.WHITE
    val iconDisabled = Color.rgb(100, 108, 120)
    val textPrimary = Color.WHITE
    val textSecondary = Color.rgb(200, 206, 214)
    val accent = Color.rgb(32, 156, 255)
    val secureBadge = Color.rgb(120, 200, 130)
    val insecureBadge = Color.rgb(230, 170, 90)
    val errorBackground = Color.rgb(28, 20, 20)
    val errorAccent = Color.rgb(230, 100, 90)

    /** Drawer panel and the dimming layer it sits on. */
    val drawerBackground = Color.rgb(16, 18, 22)
    val scrim = Color.argb(140, 0, 0, 0)

    // ---------------------------------------------------------------- menu sheet
    // Three separate surfaces, because the sheet is three levels deep: the sheet itself, the cards
    // grouping related actions on it, and the tiles on those cards. The previous menu drew the
    // panel and its tiles one RGB step apart, so the tiles had no visible edge and the grouping was
    // invisible; a control the user cannot see the boundary of is one they aim at by memory.

    /** The sheet's own background, one step above the page it floats over. */
    val sheetBackground = Color.rgb(26, 29, 35)

    /** A card grouping related tiles: recessed, so the tiles on it read as raised. */
    val sheetCardBackground = Color.rgb(17, 19, 23)

    /** A tile: the only raised surface on the sheet, and the only thing meant to be pressed. */
    val tileBackground = Color.rgb(69, 75, 92)

    /** A tile whose action is unavailable — Back with no history. Dimmed, never hidden. */
    val tileDisabledBackground = Color.rgb(46, 50, 60)

    /** Hairline above the footer, the one rule the sheet draws. */
    val hairline = Color.rgb(48, 52, 62)

    val toggleTrackOn = Color.rgb(74, 92, 210)
    val toggleTrackOff = Color.rgb(64, 69, 80)
    val toggleKnob = Color.rgb(198, 204, 245)

    // ---------------------------------------------------------------- tile icon chip
    // A tile's glyph sits in its own rounded "chip" a shade lighter than the tile, so the icon
    // reads as an object on the button rather than text printed on it. This is the phone sheet's
    // own detail — the car surface keeps its flat Canvas tiles — and it is what gives the two
    // presentations a family resemblance without either being a copy of the other.

    /** The rounded background behind a tile's glyph on an enabled tile. */
    val tileIconChip = Color.rgb(90, 98, 120)

    /** A thin accent edge down the primary (navigation) card, marking it as the first thing read. */
    val primaryCardAccent = Color.rgb(32, 156, 255)

    /** The glyph chip on the primary card picks up a hint of the accent to match its edge. */
    val primaryTileIconChip = Color.rgb(46, 86, 132)
}
