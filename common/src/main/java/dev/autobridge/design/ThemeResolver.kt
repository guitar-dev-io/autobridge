package dev.autobridge.design

/**
 * The colour roles one screen paints with, resolved from an [AppearanceSpec].
 *
 * Roles, not places: a surface asks for [surface] or [textSecondary], never "the radio card
 * colour". Values are ARGB ints so Views, Compose and the car renderer all read the same thing.
 */
data class ThemePalette(
    val isLight: Boolean,
    val background: Int,
    val surface: Int,
    val raised: Int,
    val hairline: Int,
    val text: Int,
    val textSecondary: Int,
    val textTertiary: Int,
    val accent: Int,
    val onAccent: Int,
    /** [accent] at 15% (12% in light mode), for selected fills and icon chips. */
    val accentSoft: Int,
    val good: Int,
    val warn: Int,
    val danger: Int,
    val skeleton: Int,
    /** Card fill and edge for the chosen [CardStyle]; an elevated card's edge equals its fill. */
    val cardFill: Int,
    val cardEdge: Int,
    val corners: Corners
)

object ThemeResolver {

    /**
     * Resolves [spec] to a palette. [systemDark] is Android's night mode, read for
     * [ThemeMode.SYSTEM]; [minuteOfDay] (0..1439, local time) is read for [ThemeMode.AUTO].
     */
    fun resolve(spec: AppearanceSpec, systemDark: Boolean = true, minuteOfDay: Int = 12 * 60): ThemePalette {
        val light = isLight(spec, systemDark, minuteOfDay)
        val amoled = spec.mode == ThemeMode.AMOLED
        val p = spec.preset
        val background: Int
        val surface: Int
        val raised: Int
        val hairline: Int
        when {
            light -> { background = 0xFFF2F4F8.toInt(); surface = 0xFFFFFFFF.toInt(); raised = 0xFFE8ECF2.toInt(); hairline = 0xFFD5DBE4.toInt() }
            amoled -> { background = 0xFF000000.toInt(); surface = 0xFF0E1117.toInt(); raised = 0xFF171B23.toInt(); hairline = 0xFF252B37.toInt() }
            else -> { background = p.background; surface = p.surface; raised = p.raised; hairline = p.hairline }
        }
        val accent = if (light) spec.accent.onLight else spec.accent.onDark
        val cardFill = when (spec.cardStyle) {
            CardStyle.ELEVATED -> surface
            CardStyle.OUTLINED -> 0x00000000
            CardStyle.GLASS -> withAlpha(surface, if (light) 0xCC else 0xB8)
        }
        val cardEdge = when (spec.cardStyle) {
            CardStyle.ELEVATED -> surface
            CardStyle.OUTLINED -> hairline
            CardStyle.GLASS -> if (light) 0xB3FFFFFF.toInt() else 0x1AFFFFFF
        }
        return ThemePalette(
            isLight = light,
            background = background,
            surface = surface,
            raised = raised,
            hairline = hairline,
            text = if (light) 0xFF10141B.toInt() else 0xFFF5F7FB.toInt(),
            textSecondary = if (light) 0xFF475265.toInt() else 0xFFA4B1C3.toInt(),
            textTertiary = if (light) 0xFF5B6679.toInt() else 0xFF8592A6.toInt(),
            accent = accent,
            onAccent = if (light) 0xFFFFFFFF.toInt() else 0xFF0A1424.toInt(),
            accentSoft = withAlpha(accent, if (light) 0x1F else 0x26),
            good = if (light) 0xFF1B7F45.toInt() else 0xFF5BD98A.toInt(),
            warn = if (light) 0xFF8A5A00.toInt() else 0xFFFFC65C.toInt(),
            danger = if (light) 0xFFC2263A.toInt() else 0xFFFF6B6B.toInt(),
            skeleton = if (light) 0xFFE1E6EE.toInt() else 0xFF252C38.toInt(),
            cardFill = cardFill,
            cardEdge = cardEdge,
            corners = spec.corners
        )
    }

    /** The palette the car draws with: the phone's look, or the defaults when the user turned that off. */
    fun resolveCar(spec: AppearanceSpec, systemDark: Boolean = true, minuteOfDay: Int = 12 * 60): ThemePalette =
        resolve(if (spec.carSameLook) spec else AppearanceSpec.DEFAULT, systemDark, minuteOfDay)

    fun isLight(spec: AppearanceSpec, systemDark: Boolean, minuteOfDay: Int): Boolean = when (spec.mode) {
        ThemeMode.LIGHT -> true
        ThemeMode.DARK, ThemeMode.AMOLED -> false
        ThemeMode.SYSTEM -> !systemDark
        ThemeMode.AUTO -> !isWithin(minuteOfDay, spec.autoDarkFrom, spec.autoDarkTo)
    }

    /** Whether [minute] falls in [from, to), wrapping past midnight when from > to. */
    fun isWithin(minute: Int, from: Int, to: Int): Boolean =
        if (from <= to) minute in from until to else minute >= from || minute < to

    fun withAlpha(color: Int, alpha: Int): Int = (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)
}

/**
 * How text grows with the font scale, the shape Android 14 uses: text up to 15 sp grows by the full
 * scale, text from 30 sp up by half of it, linear in between. Activities get this from the platform
 * by setting `Configuration.fontScale`; surfaces that size text in dp themselves (the car home
 * renderer) call [scaled] directly.
 */
object TextScaleCurve {
    /** Car text follows the phone up to this scale, then stops so controls stay on screen. */
    const val CAR_CAP = 1.3f

    fun scaled(base: Float, scale: Float): Float {
        val weight = when {
            base <= 15f -> 1f
            base >= 30f -> 0.5f
            else -> 1f - 0.5f * (base - 15f) / 15f
        }
        return base * (1f + (scale - 1f) * weight)
    }

    /** The scale [spec] asks for, given Android's own [systemScale]. */
    fun effectiveScale(spec: AppearanceSpec, systemScale: Float): Float =
        if (spec.followSystemText) systemScale else spec.textStep.scale

    fun carScale(spec: AppearanceSpec, systemScale: Float): Float =
        if (spec.carSameLook) effectiveScale(spec, systemScale).coerceAtMost(CAR_CAP) else 1f
}

/**
 * The palette the app is currently drawn with, for code that has no context to hand (the
 * `AutoBridgeDesign` token getters). The app's store sets it on start and on every change.
 */
object ThemeHolder {
    @Volatile
    var spec: AppearanceSpec = AppearanceSpec.DEFAULT
        private set

    @Volatile
    var palette: ThemePalette = ThemeResolver.resolve(AppearanceSpec.DEFAULT)
        private set

    @Volatile
    var carPalette: ThemePalette = ThemeResolver.resolveCar(AppearanceSpec.DEFAULT)
        private set

    fun update(spec: AppearanceSpec, systemDark: Boolean, minuteOfDay: Int) {
        this.spec = spec
        palette = ThemeResolver.resolve(spec, systemDark, minuteOfDay)
        carPalette = ThemeResolver.resolveCar(spec, systemDark, minuteOfDay)
    }
}
