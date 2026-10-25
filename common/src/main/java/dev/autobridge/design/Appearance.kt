package dev.autobridge.design

/**
 * The user's Appearance choices (Settings > Appearance), as plain data.
 *
 * Pure Kotlin so the resolver and the codec are unit-tested without a device. The store that
 * persists it lives in :app (`dev.autobridge.settings.AppearanceStore`); [ThemeResolver] turns it
 * into the [ThemePalette] every surface paints with. Defaults are the "AutoBridge" look from the
 * redesign: dark, Sky accent, Dashboard home, Glow wallpaper.
 */
data class AppearanceSpec(
    val mode: ThemeMode = ThemeMode.DARK,
    val preset: ThemePreset = ThemePreset.AUTOBRIDGE,
    val accent: AccentColor = AccentColor.SKY,
    val font: FontChoice = FontChoice.INTER,
    /** The Thai letterforms, whichever [font] draws the Latin. */
    val thai: ThaiStyle = ThaiStyle.SYSTEM,
    val textStep: TextStep = TextStep.DEFAULT,
    /** Use Android's own font size instead of [textStep]. */
    val followSystemText: Boolean = false,
    val homeLayout: HomeLayout = HomeLayout.DASHBOARD,
    /** The wallpaper for the dark themes, and for the light ones too unless [lightWallpaper] is set. */
    val wallpaper: Wallpaper = Wallpaper.GLOW,
    val wallpaperTarget: WallpaperTarget = WallpaperTarget.BOTH,
    /** 0..70, the share of black (white in light mode) laid over the wallpaper. */
    val wallpaperDim: Int = 20,
    /** 0..[WallpaperCatalog.MAX_BLUR]: how soft an image or photo is drawn. */
    val wallpaperBlur: Int = 0,
    /** Degrees, 0..360 in steps of 15, for the gradients that turn. */
    val wallpaperAngle: Int = WallpaperCatalog.DEFAULT_ANGLE,
    /** Which part of an image or photo each screen shows. */
    val phoneCrop: WallpaperCrop = WallpaperCrop.FULL,
    val carCrop: WallpaperCrop = WallpaperCrop.FULL,
    /** The light themes' own wallpaper; null keeps one wallpaper for light and dark. */
    val lightWallpaper: WallpaperLook? = null,
    val blurBehindCards: Boolean = true,
    val iconStyle: IconStyle = IconStyle.TINTED,
    val cardStyle: CardStyle = CardStyle.ELEVATED,
    val corners: Corners = Corners.SOFT,
    val showTileLabels: Boolean = true,
    /** Android Auto uses this look too; off keeps the car on [AppearanceSpec] defaults. */
    val carSameLook: Boolean = true,
    /** CPU, memory and temperature in the car home's header. */
    val carSystemStatus: Boolean = true,
    /** [ThemeMode.AUTO]: dark from this minute of the day... */
    val autoDarkFrom: Int = 18 * 60 + 30,
    /** ...until this one. */
    val autoDarkTo: Int = 6 * 60 + 30
) {
    /** The wallpaper settings of the dark themes (and of both when there is no [lightWallpaper]). */
    val darkLook: WallpaperLook
        get() = WallpaperLook(wallpaper, wallpaperDim, wallpaperBlur, wallpaperAngle, phoneCrop, carCrop)

    val separateWallpapers: Boolean get() = lightWallpaper != null

    /** The wallpaper that applies on a [light] or dark theme. */
    fun wallpaperLook(light: Boolean): WallpaperLook = if (light) lightWallpaper ?: darkLook else darkLook

    /** [look] stored for a light or dark theme: the light slot only when the wallpapers are separate. */
    fun withWallpaperLook(light: Boolean, look: WallpaperLook): AppearanceSpec =
        if (light && lightWallpaper != null) copy(lightWallpaper = look) else copy(
            wallpaper = look.wallpaper,
            wallpaperDim = look.dim,
            wallpaperBlur = look.blur,
            wallpaperAngle = look.angle,
            phoneCrop = look.phoneCrop,
            carCrop = look.carCrop
        )

    /**
     * Turns separate light and dark wallpapers [on] or off. On, both start from what is showing.
     * Off, the one that was being edited ([editingLight]) is kept for both.
     */
    fun withSeparateWallpapers(on: Boolean, editingLight: Boolean): AppearanceSpec = when {
        on == separateWallpapers -> this
        on -> copy(lightWallpaper = darkLook)
        else -> (if (editingLight) copy(lightWallpaper = null).withWallpaperLook(false, lightWallpaper ?: darkLook) else copy(lightWallpaper = null))
    }

    companion object {
        val DEFAULT = AppearanceSpec()
        const val MAX_DIM = 70
    }
}

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED, AUTO }

/** Dark surface families. A preset also suggests an accent ([suggested]). */
enum class ThemePreset(
    val background: Int,
    val surface: Int,
    val raised: Int,
    val hairline: Int,
    val suggested: AccentColor
) {
    AUTOBRIDGE(0xFF10141B.toInt(), 0xFF1A202B.toInt(), 0xFF222A37.toInt(), 0xFF2B3443.toInt(), AccentColor.SKY),
    GRAPHITE(0xFF121316.toInt(), 0xFF1C1D21.toInt(), 0xFF25272C.toInt(), 0xFF33353B.toInt(), AccentColor.TEAL),
    OCEAN(0xFF0C161B.toInt(), 0xFF152229.toInt(), 0xFF1C2C34.toInt(), 0xFF263A44.toInt(), AccentColor.TEAL),
    EMBER(0xFF14110E.toInt(), 0xFF201B17.toInt(), 0xFF2A231D.toInt(), 0xFF3A3027.toInt(), AccentColor.AMBER),
    DUSK(0xFF12111A.toInt(), 0xFF1C1B28.toInt(), 0xFF252333.toInt(), 0xFF322F44.toInt(), AccentColor.VIOLET)
}

/** The one saturated colour on a screen. [onDark] for dark and AMOLED, [onLight] for light. */
enum class AccentColor(val onDark: Int, val onLight: Int) {
    SKY(0xFF67A9FF.toInt(), 0xFF1F67C7.toInt()),
    TEAL(0xFF5FD0C4.toInt(), 0xFF0B7A70.toInt()),
    AMBER(0xFFFFB35C.toInt(), 0xFF955800.toInt()),
    VIOLET(0xFFA99BF2.toInt(), 0xFF5A46C8.toInt()),
    ROSE(0xFFFF8FA3.toInt(), 0xFFB02E4A.toInt()),
    LIME(0xFF9BE08A.toInt(), 0xFF3B7629.toInt())
}

enum class FontChoice { INTER, MANROPE, IBM_PLEX, NOTO, SYSTEM }

/**
 * Which Thai letterforms the app draws. Inter, Manrope and Plex have no Thai, so without a choice
 * the phone's own Thai font fills in. [LOOPED] has the traditional head loops (easier to read in
 * running text), [LOOPLESS] is the modern open form (cleaner at a glance, as on a head unit).
 */
enum class ThaiStyle { SYSTEM, LOOPED, LOOPLESS }

/** Manual text size steps. [scale] is the Android fontScale it stands for. */
enum class TextStep(val scale: Float) {
    SMALL(0.85f), DEFAULT(1f), LARGE(1.15f), LARGER(1.3f), HUGE(1.5f), MAX(2f)
}

enum class HomeLayout { CLASSIC, DASHBOARD, COMPACT }

/** What a wallpaper is made of, which decides the tab it sits in and what can be adjusted. */
enum class WallpaperKind { SOLID, GRADIENT, IMAGE, PHOTO }

/**
 * Every wallpaper AutoBridge can draw behind its own screens. The first six are the original
 * choices and keep their names, since the choice is stored by name; [SOLID] is the Slate tint.
 * Images are drawn in code, so they ship without files and stay sharp at any size.
 */
enum class Wallpaper(val kind: WallpaperKind) {
    NONE(WallpaperKind.SOLID), GLOW(WallpaperKind.GRADIENT), LINES(WallpaperKind.SOLID),
    NIGHT_ROAD(WallpaperKind.GRADIENT), SOLID(WallpaperKind.SOLID), CUSTOM(WallpaperKind.PHOTO),
    SOLID_NAVY(WallpaperKind.SOLID), SOLID_FOREST(WallpaperKind.SOLID), SOLID_PLUM(WallpaperKind.SOLID),
    SOLID_WINE(WallpaperKind.SOLID), SOLID_EMBER(WallpaperKind.SOLID),
    GRADIENT_AURORA(WallpaperKind.GRADIENT), GRADIENT_SUNSET(WallpaperKind.GRADIENT), GRADIENT_OCEAN(WallpaperKind.GRADIENT),
    GRADIENT_EMBER(WallpaperKind.GRADIENT), GRADIENT_VIOLET(WallpaperKind.GRADIENT), GRADIENT_MINT(WallpaperKind.GRADIENT),
    IMAGE_MIDNIGHT_ROAD(WallpaperKind.IMAGE), IMAGE_BLUE_HOUR(WallpaperKind.IMAGE), IMAGE_AMBER_DUSK(WallpaperKind.IMAGE),
    IMAGE_AURORA(WallpaperKind.IMAGE), IMAGE_CITY_LIGHTS(WallpaperKind.IMAGE), IMAGE_CALM_SEA(WallpaperKind.IMAGE),
    IMAGE_DUNES(WallpaperKind.IMAGE), IMAGE_MORNING_MIST(WallpaperKind.IMAGE)
}

enum class WallpaperTarget { PHONE, CAR, BOTH }

enum class IconStyle { TINTED, ACCENT, MONO }

enum class CardStyle { ELEVATED, OUTLINED, GLASS }

/** Corner radii in dp. */
enum class Corners(val card: Int, val tile: Int, val button: Int, val chip: Int) {
    SOFT(22, 20, 14, 14),
    STANDARD(16, 14, 12, 12),
    SHARP(8, 8, 8, 6)
}
