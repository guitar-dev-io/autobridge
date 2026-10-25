package dev.autobridge.car

import android.content.res.Resources
import dev.autobridge.design.AppearanceSpec
import dev.autobridge.design.CardStyle
import dev.autobridge.design.Corners
import dev.autobridge.design.HomeLayout
import dev.autobridge.design.FontChoice
import dev.autobridge.design.IconStyle
import dev.autobridge.design.TextScaleCurve
import dev.autobridge.design.ThaiStyle
import dev.autobridge.design.ThemeHolder
import dev.autobridge.design.ThemePalette
import dev.autobridge.design.ThemeResolver
import dev.autobridge.design.Wallpaper
import dev.autobridge.design.WallpaperCatalog
import dev.autobridge.design.WallpaperCrop
import dev.autobridge.design.WallpaperLook
import dev.autobridge.design.WallpaperTarget

/**
 * What Settings > Appearance asks of the car home, resolved to the colours and choices the
 * dashboard paints with.
 *
 * The palette is [ThemeHolder.carPalette], which is already the default theme when "Use this look
 * on Android Auto" is off; the layout, icon, card, corner, label and wallpaper choices follow the
 * same switch, so turning it off gives the car the whole default look and not half of it. Text
 * follows the phone's size up to [TextScaleCurve.CAR_CAP].
 *
 * Pure data (ARGB ints and enums), so the layout and its tests see the same values the renderer
 * draws with, and a change of any of them is a different look: the screen compares the look it
 * laid out with against [current] on every frame and lays out again when they differ.
 */
internal data class HomeDashboardLook(
    val palette: ThemePalette,
    val homeLayout: HomeLayout,
    val iconStyle: IconStyle,
    val cardStyle: CardStyle,
    val corners: Corners,
    val showTileLabels: Boolean,
    /** [Wallpaper.NONE] when the user keeps the wallpaper on the phone. */
    val wallpaper: Wallpaper,
    /** 0..70, the share of black (white in light mode) laid over the wallpaper. */
    val wallpaperDim: Int,
    /** The font scale car text is drawn at, already capped. */
    val textScale: Float,
    /** The chosen families. Text widths depend on them, so a change lays the screen out again. */
    val font: FontChoice = FontChoice.INTER,
    val thai: ThaiStyle = ThaiStyle.SYSTEM,
    /** CPU, memory and temperature in the header. */
    val systemStatus: Boolean = true,
    /** How soft an image or photo wallpaper is drawn, 0..[WallpaperCatalog.MAX_BLUR]. */
    val wallpaperBlur: Int = 0,
    /** The angle of a turning gradient wallpaper. */
    val wallpaperAngle: Int = WallpaperCatalog.DEFAULT_ANGLE,
    /** Which part of an image or photo the car shows. */
    val wallpaperCrop: WallpaperCrop = WallpaperCrop.FULL
) {
    /** The wallpaper as one value, which is what the shared painter takes. */
    val wallpaperLook: WallpaperLook
        get() = WallpaperLook(wallpaper, wallpaperDim, wallpaperBlur, wallpaperAngle, WallpaperCrop.FULL, wallpaperCrop)

    private val light: Boolean get() = palette.isLight

    // --- Surfaces ---------------------------------------------------------------------------
    val background: Int get() = palette.background

    /** Card fill for the chosen [cardStyle]: opaque, transparent (outlined) or translucent (glass). */
    val card: Int get() = palette.cardFill
    /** Drawn as a 1dp edge only when it differs from [card] (outlined and glass cards). */
    val cardEdge: Int get() = palette.cardEdge
    val hasCardEdge: Boolean get() = palette.cardEdge != palette.cardFill
    val cardFocused: Int get() = stateFill(0.07f)
    val cardPressed: Int get() = stateFill(0.13f)

    /** Opaque card colour, for mixing tints (an outlined card has no fill of its own). */
    val surface: Int get() = palette.surface

    /** Round controls, the scroll buttons, the compact now-playing strip. */
    val control: Int get() = palette.raised
    val controlPressed: Int get() = HomeDashboardTheme.mix(palette.raised, palette.text, 0.14f)
    val controlDisabledGlyph: Int get() = HomeDashboardTheme.mix(palette.raised, palette.text, 0.30f)
    val progressTrack: Int get() = palette.hairline

    val queuePill: Int get() = HomeDashboardTheme.mix(palette.surface, palette.accent, if (light) 0.12f else 0.15f)
    val queuePillPressed: Int get() = HomeDashboardTheme.mix(palette.surface, palette.accent, if (light) 0.22f else 0.27f)
    val queuePillText: Int get() = palette.accent

    val emptyOutline: Int get() = palette.hairline
    val emptyPillOutline: Int get() = HomeDashboardTheme.mix(palette.hairline, palette.text, 0.12f)

    // --- Text -------------------------------------------------------------------------------
    val textPrimary: Int get() = palette.text
    val textSecondary: Int get() = palette.textSecondary
    val textTertiary: Int get() = palette.textTertiary
    val textSection: Int get() = HomeDashboardTheme.mix(palette.textSecondary, palette.text, 0.4f)
    val textDisabled: Int get() = palette.textTertiary

    // --- Accent -----------------------------------------------------------------------------
    val accent: Int get() = palette.accent
    val onAccent: Int get() = palette.onAccent
    /** [accent] mixed into the card colour: the art tile of the compact strip, the empty-state tile. */
    val accentSoft: Int get() = HomeDashboardTheme.mix(palette.surface, palette.accent, if (light) 0.12f else 0.15f)

    // --- Radii (dp) -------------------------------------------------------------------------
    /** Dashboard cards and the Now Playing card. */
    val cardRadius: Float get() = corners.card.toFloat()
    /** Classic grid tiles. */
    val tileRadius: Float get() = corners.tile.toFloat()
    /** Compact rows, the compact strip, Recently Sent rows. */
    val buttonRadius: Float get() = corners.button.toFloat()

    /** An icon chip's radius at [size] dp: the design's chip radius is drawn for a 52dp chip. */
    fun chipRadius(size: Float): Float = corners.chip * size / DESIGN_CHIP

    /** The Now Playing card's artwork. */
    val artRadius: Float get() = corners.chip + 2f

    /** [base] dp of text at this look's [textScale], before the layout's own scale. */
    fun text(base: Float): Float = TextScaleCurve.scaled(base, textScale)

    /**
     * A feature's own colour as this mode shows it: as authored on dark, darkened on light so a
     * pale hue still reads on a white card.
     */
    fun hue(featureAccent: Int): Int =
        if (light) HomeDashboardTheme.mix(featureAccent or OPAQUE, 0xFF000000.toInt(), LIGHT_HUE_DARKEN) else featureAccent

    /** How an icon chip for a feature of [featureAccent] is painted under [iconStyle]. */
    fun chip(featureAccent: Int): Chip = when (iconStyle) {
        IconStyle.TINTED -> {
            val h = hue(featureAccent)
            Chip(
                fill = HomeDashboardTheme.mix(palette.surface, h, if (light) 0.12f else HomeDashboardTheme.ICON_TILE_TINT * 2f),
                edge = HomeDashboardTheme.mix(palette.surface, h, HomeDashboardTheme.ICON_TILE_BORDER_TINT),
                glyph = h,
                cutout = WHITE
            )
        }
        IconStyle.ACCENT -> Chip(fill = palette.accent, edge = palette.accent, glyph = palette.onAccent, cutout = palette.accent)
        IconStyle.MONO -> Chip(fill = palette.raised, edge = palette.raised, glyph = palette.text, cutout = palette.raised)
    }

    /**
     * An icon chip's colours. [glyph] draws the feature's mark; [cutout] is what shows through
     * it (the play triangle on YouTube), which has to differ from [glyph] in every style.
     */
    data class Chip(val fill: Int, val edge: Int, val glyph: Int, val cutout: Int) {
        val hasEdge: Boolean get() = edge != fill
    }

    /** The focused and pressed card: the card lifted towards the text colour, keeping its alpha. */
    private fun stateFill(t: Float): Int {
        val alpha = (palette.cardFill ushr 24) and 0xFF
        val lifted = HomeDashboardTheme.mix(palette.surface, palette.text, t)
        // An outlined card has no fill; its pressed state still needs one to show the press.
        return if (alpha == 0) HomeDashboardTheme.withAlpha(lifted, 0.6f) else (alpha shl 24) or (lifted and 0x00FFFFFF)
    }

    companion object {
        private const val OPAQUE = 0xFF000000.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val DESIGN_CHIP = 52f
        private const val LIGHT_HUE_DARKEN = 0.42f

        /**
         * The look for [spec] and the car [palette] resolved from it. [systemFontScale] is
         * Android's own font size, read when the user follows it.
         */
        fun of(spec: AppearanceSpec, palette: ThemePalette, systemFontScale: Float): HomeDashboardLook {
            val car = if (spec.carSameLook) spec else AppearanceSpec.DEFAULT
            val wallpaperShown = car.wallpaperTarget == WallpaperTarget.CAR || car.wallpaperTarget == WallpaperTarget.BOTH
            val wall = car.wallpaperLook(palette.isLight)
            return HomeDashboardLook(
                palette = palette,
                homeLayout = car.homeLayout,
                iconStyle = car.iconStyle,
                cardStyle = car.cardStyle,
                corners = car.corners,
                showTileLabels = car.showTileLabels,
                wallpaper = if (wallpaperShown) wall.wallpaper else Wallpaper.NONE,
                wallpaperDim = wall.dim.coerceIn(0, AppearanceSpec.MAX_DIM),
                wallpaperBlur = wall.blur,
                wallpaperAngle = wall.angle,
                wallpaperCrop = wall.carCrop,
                textScale = TextScaleCurve.carScale(spec, systemFontScale),
                font = car.font,
                thai = car.thai,
                systemStatus = car.carSystemStatus
            )
        }

        /** The look the app is set to right now. Cheap: a few field reads, no I/O. */
        fun current(): HomeDashboardLook =
            of(ThemeHolder.spec, ThemeHolder.carPalette, Resources.getSystem().configuration.fontScale)

        /** The default look, which is what the layout tests and an unset store draw with. */
        val DEFAULT: HomeDashboardLook =
            of(AppearanceSpec.DEFAULT, ThemeResolver.resolveCar(AppearanceSpec.DEFAULT), 1f)
    }
}
