package dev.autobridge.browser

import android.graphics.Color

/**
 * Single source of truth for browser colors, shared by the phone [BrowserActivity] (native Views)
 * and the car [CarWebRenderer] (Canvas-drawn chrome), so both presentations read as one app the way
 * a Fermata-style browser keeps its toolbar/menu look consistent across screens.
 *
 * The values are a Material 3 **dark** colour scheme generated from a blue seed (the app's
 * `#4C7DF0` accent), using the M3 role names in the comments: surfaces are the tonal
 * `surfaceContainer*` ladder rather than ad-hoc greys, the accent is `primary`, and filled controls
 * use `primaryContainer` / `secondaryContainer`. A car display is always treated as dark here —
 * Android Auto runs its own UI dark at night and most of the day, and a light page chrome over a
 * dark host frame reads as a hole in the dashboard.
 *
 * Sizing deliberately lives in [AutoUiSizes], not here. This object used to also expose a
 * `chromeScale(shortestWidthDp)` that grew icons with the screen's *dimension*, which is what made
 * a wide head unit render toolbar glyphs at tablet size.
 */
object BrowserTheme {
    // ---------------------------------------------------------------- M3 roles (dark scheme)
    val primary = Color.rgb(0xAA, 0xC7, 0xFF)
    val onPrimary = Color.rgb(0x0A, 0x30, 0x5F)
    val primaryContainer = Color.rgb(0x28, 0x47, 0x77)
    val onPrimaryContainer = Color.rgb(0xD6, 0xE3, 0xFF)
    val secondaryContainer = Color.rgb(0x3E, 0x47, 0x59)
    val onSecondaryContainer = Color.rgb(0xDA, 0xE2, 0xF9)
    val surface = Color.rgb(0x11, 0x13, 0x18)
    val surfaceContainerLowest = Color.rgb(0x0C, 0x0E, 0x13)
    val surfaceContainerLow = Color.rgb(0x19, 0x1C, 0x20)
    val surfaceContainer = Color.rgb(0x1D, 0x20, 0x24)
    val surfaceContainerHigh = Color.rgb(0x28, 0x2A, 0x2F)
    val surfaceContainerHighest = Color.rgb(0x33, 0x35, 0x3A)
    val onSurface = Color.rgb(0xE2, 0xE2, 0xE9)
    val onSurfaceVariant = Color.rgb(0xC4, 0xC6, 0xD0)
    val outline = Color.rgb(0x8E, 0x90, 0x99)
    val outlineVariant = Color.rgb(0x44, 0x47, 0x4E)
    val error = Color.rgb(0xFF, 0xB4, 0xAB)

    // ---------------------------------------------------------------- browser chrome
    val background = surface
    val toolbarBackground = surfaceContainer
    val addressPillBackground = surfaceContainerHighest
    val iconEnabled = onSurface

    /** onSurface at the M3 disabled opacity (38%), pre-blended so it stays opaque on any surface. */
    val iconDisabled = Color.rgb(0x68, 0x6A, 0x6F)
    val textPrimary = onSurface
    val textSecondary = onSurfaceVariant
    val accent = primary

    /** Tone-80 green / amber: M3 has no success role, so these sit at the same tone as primary. */
    val secureBadge = Color.rgb(0x9C, 0xD6, 0x7D)
    val insecureBadge = Color.rgb(0xF3, 0xBD, 0x6E)
    val errorBackground = surface
    val errorAccent = error

    /** M3 FAB: primaryContainer with onPrimaryContainer content. */
    val fabContainer = primaryContainer
    val onFabContainer = onPrimaryContainer

    /** Tab switcher backdrop. */
    val drawerBackground = surfaceContainerLow

    /** M3 scrim is black at 32%. */
    val scrim = Color.argb(82, 0, 0, 0)

    // ---------------------------------------------------------------- menu sheet
    // Three separate surfaces, because the sheet is three levels deep: the sheet itself, the cards
    // grouping related actions on it, and the tiles on those cards. Each sits one step up the
    // surfaceContainer ladder, and the tiles are tonal (secondaryContainer) buttons, so every
    // pressable thing has a visible edge.

    /** M3 bottom sheets use surfaceContainerLow. */
    val sheetBackground = surfaceContainerLow

    /** A card grouping related tiles. */
    val sheetCardBackground = surfaceContainerHigh

    /** A tile: an M3 filled-tonal button. */
    val tileBackground = secondaryContainer

    /** onSurface at 12% over the card — the M3 disabled container treatment. */
    val tileDisabledBackground = Color.rgb(0x3E, 0x40, 0x45)

    /** Hairline above the footer, the one rule the sheet draws. */
    val hairline = outlineVariant

    /** M3 switch: primary track + onPrimary handle when on; outline handle on a dim track when off. */
    val toggleTrackOn = primary
    val toggleTrackOff = surfaceContainerHighest
    val toggleKnob = onPrimary
    val toggleKnobOff = outline

    // ---------------------------------------------------------------- tile icon chip
    // A tile's glyph sits in its own rounded "chip" a shade lighter than the tile, so the icon
    // reads as an object on the button rather than text printed on it. This is the phone sheet's
    // own detail — the car surface keeps its flat Canvas tiles.

    /** The rounded background behind a tile's glyph on an enabled tile. */
    val tileIconChip = Color.rgb(0x55, 0x5F, 0x73)

    /** A thin accent edge down the primary (navigation) card, marking it as the first thing read. */
    val primaryCardAccent = primary

    /** The glyph chip on the primary card picks up the accent to match its edge. */
    val primaryTileIconChip = primaryContainer
}
