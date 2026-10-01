package dev.autobridge.browser

import android.graphics.Color

/**
 * One complete colour scheme for the browser, in Material 3 role names.
 *
 * The roles that are a straight alias of another one ([background] is the surface, [accent] is the
 * primary, and so on) are derived here rather than passed in, so a scheme can only be defined in
 * one way and the two schemes cannot drift apart in structure — only in colour. The handful that
 * are not derivable (a pre-blended disabled grey, the security badges, the tile chip) are given
 * per scheme, because blending against a dark surface and against a light one give different
 * answers and neither can be computed from the other.
 */
class BrowserColors(
    // ---------------------------------------------------------------- M3 roles
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val surface: Int,
    val surfaceContainerLowest: Int,
    val surfaceContainerLow: Int,
    val surfaceContainer: Int,
    val surfaceContainerHigh: Int,
    val surfaceContainerHighest: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val outline: Int,
    val outlineVariant: Int,
    val error: Int,
    // ---------------------------------------------------------------- hand-tuned per scheme
    /** onSurface at the M3 disabled opacity (38%), pre-blended so it stays opaque on any surface. */
    val iconDisabled: Int,
    /** Green / amber at the scheme's own tone: M3 has no success role. */
    val secureBadge: Int,
    val insecureBadge: Int,
    /** onSurface at 12% over the card — the M3 disabled container treatment. */
    val tileDisabledBackground: Int,
    /** The rounded background behind a tile's glyph on an enabled tile. */
    val tileIconChip: Int,
) {
    // ---------------------------------------------------------------- browser chrome
    val background = surface
    val toolbarBackground = surfaceContainer
    val addressPillBackground = surfaceContainerHighest
    val iconEnabled = onSurface
    val textPrimary = onSurface
    val textSecondary = onSurfaceVariant
    val accent = primary
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

    /** Hairline above the footer, the one rule the sheet draws. */
    val hairline = outlineVariant

    /** M3 switch: primary track + onPrimary handle when on; outline handle on a dim track when off. */
    val toggleTrackOn = primary
    val toggleTrackOff = surfaceContainerHighest
    val toggleKnob = onPrimary
    val toggleKnobOff = outline

    /** A thin accent edge down the primary (navigation) card, marking it as the first thing read. */
    val primaryCardAccent = primary

    /** The glyph chip on the primary card picks up the accent to match its edge. */
    val primaryTileIconChip = primaryContainer
}

/**
 * Single source of truth for browser colors, shared by the phone [BrowserActivity] (native Views)
 * and the car [CarWebRenderer] (Canvas-drawn chrome), so both presentations read as one app the way
 * a Fermata-style browser keeps its toolbar/menu look consistent across screens.
 *
 * Two Material 3 schemes generated from one blue seed (the app's `#4C7DF0` accent): [dark], and
 * [light] at the same tones the car theme's `carColorPrimary` uses. The phone follows the
 * Appearance setting through [active]; the car deliberately reads [dark] directly. A head unit runs
 * its own UI dark at night and most of the day, and a light page chrome inside a dark dashboard
 * frame reads as a hole in it — so "Light" is a phone choice, not a car one.
 *
 * Call sites read the role off this object ([BrowserTheme.toolbarBackground]) and get whichever
 * scheme is active at the time they draw, which is why a theme change rebuilds the browser's views
 * rather than trying to re-tint them in place.
 *
 * Sizing deliberately lives in [AutoUiSizes], not here. This object used to also expose a
 * `chromeScale(shortestWidthDp)` that grew icons with the screen's *dimension*, which is what made
 * a wide head unit render toolbar glyphs at tablet size.
 */
object BrowserTheme {

    /** The dark scheme: the one the car always draws, and the phone's default. */
    val dark = BrowserColors(
        primary = Color.rgb(0xAA, 0xC7, 0xFF),
        onPrimary = Color.rgb(0x0A, 0x30, 0x5F),
        primaryContainer = Color.rgb(0x28, 0x47, 0x77),
        onPrimaryContainer = Color.rgb(0xD6, 0xE3, 0xFF),
        secondaryContainer = Color.rgb(0x3E, 0x47, 0x59),
        onSecondaryContainer = Color.rgb(0xDA, 0xE2, 0xF9),
        surface = Color.rgb(0x11, 0x13, 0x18),
        surfaceContainerLowest = Color.rgb(0x0C, 0x0E, 0x13),
        surfaceContainerLow = Color.rgb(0x19, 0x1C, 0x20),
        surfaceContainer = Color.rgb(0x1D, 0x20, 0x24),
        surfaceContainerHigh = Color.rgb(0x28, 0x2A, 0x2F),
        surfaceContainerHighest = Color.rgb(0x33, 0x35, 0x3A),
        onSurface = Color.rgb(0xE2, 0xE2, 0xE9),
        onSurfaceVariant = Color.rgb(0xC4, 0xC6, 0xD0),
        outline = Color.rgb(0x8E, 0x90, 0x99),
        outlineVariant = Color.rgb(0x44, 0x47, 0x4E),
        error = Color.rgb(0xFF, 0xB4, 0xAB),
        iconDisabled = Color.rgb(0x68, 0x6A, 0x6F),
        secureBadge = Color.rgb(0x9C, 0xD6, 0x7D),
        insecureBadge = Color.rgb(0xF3, 0xBD, 0x6E),
        tileDisabledBackground = Color.rgb(0x3E, 0x40, 0x45),
        tileIconChip = Color.rgb(0x55, 0x5F, 0x73),
    )

    /**
     * The light scheme: the same M3 roles at light tones, so every surface keeps its place in the
     * ladder (the sheet is still a step off the page, a card a step off the sheet) and nothing in
     * the chrome has to know which scheme it is drawing.
     */
    val light = BrowserColors(
        primary = Color.rgb(0x41, 0x5F, 0x91),
        onPrimary = Color.rgb(0xFF, 0xFF, 0xFF),
        primaryContainer = Color.rgb(0xD6, 0xE3, 0xFF),
        onPrimaryContainer = Color.rgb(0x28, 0x47, 0x77),
        secondaryContainer = Color.rgb(0xDA, 0xE2, 0xF9),
        onSecondaryContainer = Color.rgb(0x3E, 0x47, 0x59),
        surface = Color.rgb(0xF9, 0xF9, 0xFF),
        surfaceContainerLowest = Color.rgb(0xFF, 0xFF, 0xFF),
        surfaceContainerLow = Color.rgb(0xF3, 0xF3, 0xFA),
        surfaceContainer = Color.rgb(0xED, 0xED, 0xF4),
        surfaceContainerHigh = Color.rgb(0xE7, 0xE8, 0xEE),
        surfaceContainerHighest = Color.rgb(0xE2, 0xE2, 0xE9),
        onSurface = Color.rgb(0x19, 0x1C, 0x20),
        onSurfaceVariant = Color.rgb(0x44, 0x47, 0x4E),
        outline = Color.rgb(0x74, 0x77, 0x7F),
        outlineVariant = Color.rgb(0xC4, 0xC6, 0xD0),
        error = Color.rgb(0xBA, 0x1A, 0x1A),
        // onSurface at 38% over surfaceContainer, and at 12% over the card, pre-blended.
        iconDisabled = Color.rgb(0x9C, 0x9D, 0xA3),
        secureBadge = Color.rgb(0x3F, 0x6A, 0x32),
        insecureBadge = Color.rgb(0x7A, 0x59, 0x00),
        tileDisabledBackground = Color.rgb(0xCE, 0xCF, 0xD5),
        tileIconChip = Color.rgb(0xC6, 0xCF, 0xE6),
    )

    /**
     * The scheme the phone chrome draws with, set from the Appearance setting by
     * [BrowserAppearanceStore.syncChrome] before any browser view is built. Written on the main
     * thread and read from the main thread and the car renderer's draw, hence volatile.
     */
    @Volatile
    var active: BrowserColors = dark

    // ---------------------------------------------------------------- M3 roles (active scheme)
    val primary: Int get() = active.primary
    val onPrimary: Int get() = active.onPrimary
    val primaryContainer: Int get() = active.primaryContainer
    val onPrimaryContainer: Int get() = active.onPrimaryContainer
    val secondaryContainer: Int get() = active.secondaryContainer
    val onSecondaryContainer: Int get() = active.onSecondaryContainer
    val surface: Int get() = active.surface
    val surfaceContainerLowest: Int get() = active.surfaceContainerLowest
    val surfaceContainerLow: Int get() = active.surfaceContainerLow
    val surfaceContainer: Int get() = active.surfaceContainer
    val surfaceContainerHigh: Int get() = active.surfaceContainerHigh
    val surfaceContainerHighest: Int get() = active.surfaceContainerHighest
    val onSurface: Int get() = active.onSurface
    val onSurfaceVariant: Int get() = active.onSurfaceVariant
    val outline: Int get() = active.outline
    val outlineVariant: Int get() = active.outlineVariant
    val error: Int get() = active.error

    // ---------------------------------------------------------------- browser chrome
    val background: Int get() = active.background
    val toolbarBackground: Int get() = active.toolbarBackground
    val addressPillBackground: Int get() = active.addressPillBackground
    val iconEnabled: Int get() = active.iconEnabled
    val iconDisabled: Int get() = active.iconDisabled
    val textPrimary: Int get() = active.textPrimary
    val textSecondary: Int get() = active.textSecondary
    val accent: Int get() = active.accent
    val secureBadge: Int get() = active.secureBadge
    val insecureBadge: Int get() = active.insecureBadge
    val errorBackground: Int get() = active.errorBackground
    val errorAccent: Int get() = active.errorAccent
    val fabContainer: Int get() = active.fabContainer
    val onFabContainer: Int get() = active.onFabContainer
    val drawerBackground: Int get() = active.drawerBackground
    val scrim: Int get() = active.scrim

    // ---------------------------------------------------------------- menu sheet
    val sheetBackground: Int get() = active.sheetBackground
    val sheetCardBackground: Int get() = active.sheetCardBackground
    val tileBackground: Int get() = active.tileBackground
    val tileDisabledBackground: Int get() = active.tileDisabledBackground
    val hairline: Int get() = active.hairline
    val toggleTrackOn: Int get() = active.toggleTrackOn
    val toggleTrackOff: Int get() = active.toggleTrackOff
    val toggleKnob: Int get() = active.toggleKnob
    val toggleKnobOff: Int get() = active.toggleKnobOff
    val tileIconChip: Int get() = active.tileIconChip
    val primaryCardAccent: Int get() = active.primaryCardAccent
    val primaryTileIconChip: Int get() = active.primaryTileIconChip
}
