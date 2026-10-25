package dev.autobridge.browser

import android.graphics.Color
import dev.autobridge.design.DesignTokens
import dev.autobridge.design.ThemeHolder
import dev.autobridge.design.ThemePalette

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
    /**
     * The design system's "selected" fill: the accent at 15%, drawn under an accent outline and an
     * accent label. Translucent on purpose, so it reads on whichever surface it sits on.
     */
    val selectedFill: Int = (0x26 shl 24) or (primary and 0x00FFFFFF),
    /** The floating button: a near-opaque surface with an accent edge. */
    val fabFill: Int = DesignTokens.BrowserDark.FAB_FILL,
    val fabEdge: Int = DesignTokens.BrowserDark.FAB_EDGE,
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
 * Two fixed Material 3 schemes generated from one blue seed (the app's `#4C7DF0` accent): [dark],
 * and [light] at the same tones the car theme's `carColorPrimary` uses. The phone draws with
 * [active], which [BrowserAppearanceStore.syncChrome] builds from the app's Appearance palette
 * ([fromPalette]). The car draws with [car], built the same way from the car palette
 * ([ThemeHolder.carPalette]), so the car browser follows Settings > Appearance like every other car
 * screen, and keeps the default look when "Use this look on Android Auto" is off.
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
        primary = DesignTokens.BrowserDark.PRIMARY,
        onPrimary = DesignTokens.BrowserDark.ON_PRIMARY,
        primaryContainer = DesignTokens.BrowserDark.PRIMARY_CONTAINER,
        onPrimaryContainer = DesignTokens.BrowserDark.ON_PRIMARY_CONTAINER,
        secondaryContainer = DesignTokens.BrowserDark.SECONDARY_CONTAINER,
        onSecondaryContainer = DesignTokens.BrowserDark.ON_SECONDARY_CONTAINER,
        surface = DesignTokens.BrowserDark.SURFACE,
        surfaceContainerLowest = DesignTokens.BrowserDark.SURFACE_CONTAINER_LOWEST,
        surfaceContainerLow = DesignTokens.BrowserDark.SURFACE_CONTAINER_LOW,
        surfaceContainer = DesignTokens.BrowserDark.SURFACE_CONTAINER,
        surfaceContainerHigh = DesignTokens.BrowserDark.SURFACE_CONTAINER_HIGH,
        surfaceContainerHighest = DesignTokens.BrowserDark.SURFACE_CONTAINER_HIGHEST,
        onSurface = DesignTokens.BrowserDark.ON_SURFACE,
        onSurfaceVariant = DesignTokens.BrowserDark.ON_SURFACE_VARIANT,
        outline = DesignTokens.BrowserDark.OUTLINE,
        outlineVariant = DesignTokens.BrowserDark.OUTLINE_VARIANT,
        error = DesignTokens.BrowserDark.ERROR,
        iconDisabled = DesignTokens.BrowserDark.ICON_DISABLED,
        secureBadge = DesignTokens.BrowserDark.SECURE_BADGE,
        insecureBadge = DesignTokens.BrowserDark.INSECURE_BADGE,
        tileDisabledBackground = DesignTokens.BrowserDark.TILE_DISABLED_BACKGROUND,
        tileIconChip = DesignTokens.BrowserDark.TILE_ICON_CHIP,
    )

    /**
     * The light scheme: the same M3 roles at light tones, so every surface keeps its place in the
     * ladder (the sheet is still a step off the page, a card a step off the sheet) and nothing in
     * the chrome has to know which scheme it is drawing.
     */
    val light = BrowserColors(
        primary = DesignTokens.BrowserLight.PRIMARY,
        onPrimary = DesignTokens.BrowserLight.ON_PRIMARY,
        primaryContainer = DesignTokens.BrowserLight.PRIMARY_CONTAINER,
        onPrimaryContainer = DesignTokens.BrowserLight.ON_PRIMARY_CONTAINER,
        secondaryContainer = DesignTokens.BrowserLight.SECONDARY_CONTAINER,
        onSecondaryContainer = DesignTokens.BrowserLight.ON_SECONDARY_CONTAINER,
        surface = DesignTokens.BrowserLight.SURFACE,
        surfaceContainerLowest = DesignTokens.BrowserLight.ON_PRIMARY,
        surfaceContainerLow = DesignTokens.BrowserLight.SURFACE_CONTAINER_LOW,
        surfaceContainer = DesignTokens.BrowserLight.SURFACE_CONTAINER,
        surfaceContainerHigh = DesignTokens.BrowserLight.SURFACE_CONTAINER_HIGH,
        surfaceContainerHighest = DesignTokens.BrowserLight.SURFACE_CONTAINER_HIGHEST,
        onSurface = DesignTokens.BrowserLight.ON_SURFACE,
        onSurfaceVariant = DesignTokens.BrowserLight.ON_SURFACE_VARIANT,
        outline = DesignTokens.BrowserLight.OUTLINE,
        outlineVariant = DesignTokens.BrowserLight.OUTLINE_VARIANT,
        error = DesignTokens.BrowserLight.ERROR,
        // onSurface at 38% over surfaceContainer, and at 12% over the card, pre-blended.
        iconDisabled = DesignTokens.BrowserLight.ICON_DISABLED,
        secureBadge = DesignTokens.BrowserLight.SECURE_BADGE,
        insecureBadge = DesignTokens.BrowserLight.INSECURE_BADGE,
        tileDisabledBackground = DesignTokens.BrowserLight.TILE_DISABLED_BACKGROUND,
        tileIconChip = DesignTokens.BrowserLight.TILE_ICON_CHIP,
    )

    /**
     * The scheme the phone chrome draws with, set from the Appearance setting by
     * [BrowserAppearanceStore.syncChrome] before any browser view is built. Written on the main
     * thread and read from the main thread and the car renderer's draw, hence volatile.
     */
    @Volatile
    var active: BrowserColors = dark

    /**
     * The phone chrome's scheme, derived from the app's Settings > Appearance palette so the browser
     * picks up the same preset, accent and light/dark choice as every other phone screen. [car]
     * comes through here too, with the car palette.
     *
     * The palette has four surface steps (background, surface, raised, hairline) where Material 3
     * has a ladder of six, so the missing rungs are blended a few percent toward the text colour.
     * That keeps the structure the chrome relies on (the sheet a step off the page, a card a step
     * off the sheet, a tile a step off the card) in every preset, light or dark.
     */
    fun fromPalette(palette: ThemePalette): BrowserColors {
        val ink = palette.text
        val highest = blend(palette.raised, ink, 0.06f)
        val tile = blend(palette.raised, ink, 0.10f)
        val accentContainer = blend(palette.raised, palette.accent, if (palette.isLight) 0.18f else 0.30f)
        return BrowserColors(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            primaryContainer = accentContainer,
            onPrimaryContainer = ink,
            secondaryContainer = tile,
            onSecondaryContainer = ink,
            surface = palette.background,
            surfaceContainerLowest = palette.background,
            surfaceContainerLow = palette.surface,
            surfaceContainer = palette.surface,
            surfaceContainerHigh = palette.raised,
            surfaceContainerHighest = highest,
            onSurface = ink,
            onSurfaceVariant = palette.textSecondary,
            // A switch's off knob is drawn in [outline], so it takes the tertiary text tone: the
            // hairline itself would vanish against the off track. Rules use [outlineVariant].
            outline = palette.textTertiary,
            outlineVariant = palette.hairline,
            error = palette.danger,
            iconDisabled = blend(palette.surface, ink, 0.38f),
            secureBadge = palette.good,
            insecureBadge = palette.warn,
            tileDisabledBackground = blend(palette.raised, ink, 0.12f),
            tileIconChip = blend(tile, ink, 0.10f),
            selectedFill = palette.accentSoft,
            fabFill = (0xE6 shl 24) or (palette.surface and 0x00FFFFFF),
            fabEdge = palette.accent,
        )
    }

    @Volatile
    private var carCache: Pair<ThemePalette, BrowserColors>? = null

    /**
     * The car chrome's scheme: [fromPalette] of the car palette, built once per palette. Read on
     * every chrome frame, so a change of look on the phone shows on the next frame.
     */
    fun car(palette: ThemePalette = ThemeHolder.carPalette): BrowserColors {
        carCache?.let { (from, colors) -> if (from === palette || from == palette) return colors }
        return fromPalette(palette).also { carCache = palette to it }
    }

    /**
     * [from] moved [amount] (0..1) of the way to [to], per channel; always opaque. Plain bit
     * arithmetic rather than [Color], so the JVM tests can call it.
     */
    internal fun blend(from: Int, to: Int, amount: Float): Int {
        fun mix(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return ((a + (b - a) * amount + 0.5f).toInt().coerceIn(0, 255)) shl shift
        }
        return (0xFF shl 24) or mix(16) or mix(8) or mix(0)
    }

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
    val selectedFill: Int get() = active.selectedFill
    val fabFill: Int get() = active.fabFill
    val fabEdge: Int get() = active.fabEdge
}
