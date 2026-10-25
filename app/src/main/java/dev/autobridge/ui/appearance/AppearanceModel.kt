package dev.autobridge.ui.appearance

import dev.autobridge.design.AppearanceSpec
import dev.autobridge.design.Corners
import dev.autobridge.design.IconStyle
import dev.autobridge.design.TextScaleCurve
import dev.autobridge.design.ThemePalette
import dev.autobridge.design.ThemeResolver
import dev.autobridge.design.WallpaperCatalog
import dev.autobridge.design.WallpaperTarget
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The plain decisions behind the Appearance pages, kept apart from the Views so they are tested on
 * the JVM: the resulting-sizes table, icon chip colours, the small radii the previews use, the dim
 * slider steps, the time labels and the size a picked photo is saved at.
 */
object AppearanceModel {

    /** The sub-pages of Settings > Appearance, shown inside [dev.autobridge.ui.AppearanceScreen]. */
    enum class Page { MAIN, THEME, TEXT, WALLPAPER, ICONS }

    /** Where Back goes from [page]: every sub-page returns to the main page, which leaves. */
    fun parent(page: Page): Page? = if (page == Page.MAIN) null else Page.MAIN

    // ----------------------------------------------------------------------------- text sizes

    /** One line of "Resulting sizes": the style's base size on the phone and on the car. */
    data class SizeRow(val style: TextStyle, val phoneBase: Int, val phone: Int, val carBase: Int, val car: Int)

    enum class TextStyle(val phoneBase: Int, val carBase: Int) {
        DISPLAY(24, 36), TITLE(20, 28), HEADING(17, 24), BODY(15, 20), CAPTION(13, 17), OVERLINE(11, 14)
    }

    fun sizeRows(spec: AppearanceSpec, systemScale: Float): List<SizeRow> {
        val phoneScale = TextScaleCurve.effectiveScale(spec, systemScale)
        val carScale = TextScaleCurve.carScale(spec, systemScale)
        return TextStyle.entries.map {
            SizeRow(
                style = it,
                phoneBase = it.phoneBase,
                phone = TextScaleCurve.scaled(it.phoneBase.toFloat(), phoneScale).roundToInt(),
                carBase = it.carBase,
                car = TextScaleCurve.scaled(it.carBase.toFloat(), carScale).roundToInt()
            )
        }
    }

    /** The note under the size steps shows from this scale up (Larger, 130%). */
    const val LARGE_TEXT_NOTE_FROM = 1.3f

    fun showsLargeTextNote(spec: AppearanceSpec, systemScale: Float): Boolean =
        TextScaleCurve.effectiveScale(spec, systemScale) >= LARGE_TEXT_NOTE_FROM - 0.001f

    fun percent(scale: Float): Int = (scale * 100).roundToInt()

    // ------------------------------------------------------------------------------ icon chips

    /** Hue per home destination, dark-mode and light-mode, as the prototype's category colours. */
    enum class Category(val onDark: Int, val onLight: Int) {
        BROWSER(0xFF70ACFF.toInt(), 0xFF1F63C2.toInt()),
        MUSIC(0xFFAF80F8.toInt(), 0xFF6A3FC0.toInt()),
        RADIO(0xFFFFC46B.toInt(), 0xFF8F5800.toInt()),
        TV(0xFFFF7A7A.toInt(), 0xFFB3282E.toInt()),
        MIRROR(0xFF7CE1CB.toInt(), 0xFF0D7563.toInt()),
        MY_CAR(0xFF86E3AF.toInt(), 0xFF1E7A45.toInt())
    }

    data class ChipColors(val fill: Int, val glyph: Int)

    /** The fill and glyph colour of an icon chip in [style]. */
    fun chip(style: IconStyle, category: Category, palette: ThemePalette): ChipColors = when (style) {
        IconStyle.ACCENT -> ChipColors(palette.accent, palette.onAccent)
        IconStyle.MONO -> ChipColors(palette.raised, palette.text)
        IconStyle.TINTED -> {
            val hue = if (palette.isLight) category.onLight else category.onDark
            ChipColors(ThemeResolver.withAlpha(hue, if (palette.isLight) 0x1F else 0x29), hue)
        }
    }

    // ----------------------------------------------------------------------------------- radii

    /** Radii for the small things on these pages, which [Corners] does not carry. In dp. */
    data class SmallRadii(val chipSmall: Int, val mini: Int, val miniChip: Int)

    fun smallRadii(corners: Corners): SmallRadii = when (corners) {
        Corners.SOFT -> SmallRadii(chipSmall = 11, mini = 8, miniChip = 6)
        Corners.STANDARD -> SmallRadii(chipSmall = 9, mini = 6, miniChip = 5)
        Corners.SHARP -> SmallRadii(chipSmall = 6, mini = 3, miniChip = 3)
    }

    // -------------------------------------------------------------------------------- wallpaper

    /** The dim slider moves in steps of 5, 0 to [AppearanceSpec.MAX_DIM]. */
    const val DIM_STEP = 5
    val DIM_STEPS = AppearanceSpec.MAX_DIM / DIM_STEP

    fun dimToProgress(dim: Int): Int = (dim.coerceIn(0, AppearanceSpec.MAX_DIM) + DIM_STEP / 2) / DIM_STEP

    fun progressToDim(progress: Int): Int = (progress.coerceIn(0, DIM_STEPS) * DIM_STEP)

    /** Blur moves in steps of [WallpaperCatalog.BLUR_STEP] up to [WallpaperCatalog.MAX_BLUR]. */
    val BLUR_STEPS = WallpaperCatalog.MAX_BLUR / WallpaperCatalog.BLUR_STEP
    fun blurToProgress(blur: Int): Int = (blur.coerceIn(0, WallpaperCatalog.MAX_BLUR) + WallpaperCatalog.BLUR_STEP / 2) / WallpaperCatalog.BLUR_STEP
    fun progressToBlur(progress: Int): Int = progress.coerceIn(0, BLUR_STEPS) * WallpaperCatalog.BLUR_STEP

    /** Zoom moves from [WallpaperCatalog.ZOOM_MIN] to [WallpaperCatalog.ZOOM_MAX] in steps of [WallpaperCatalog.ZOOM_STEP] percent. */
    val ZOOM_STEPS = (WallpaperCatalog.ZOOM_MAX - WallpaperCatalog.ZOOM_MIN) / WallpaperCatalog.ZOOM_STEP
    fun zoomToProgress(zoom: Int): Int =
        (zoom.coerceIn(WallpaperCatalog.ZOOM_MIN, WallpaperCatalog.ZOOM_MAX) - WallpaperCatalog.ZOOM_MIN + WallpaperCatalog.ZOOM_STEP / 2) / WallpaperCatalog.ZOOM_STEP
    fun progressToZoom(progress: Int): Int = WallpaperCatalog.ZOOM_MIN + progress.coerceIn(0, ZOOM_STEPS) * WallpaperCatalog.ZOOM_STEP

    /** The gradient angle turns in steps of [WallpaperCatalog.ANGLE_STEP], 0 to 360. */
    val ANGLE_STEPS = 360 / WallpaperCatalog.ANGLE_STEP
    fun angleToProgress(angle: Int): Int = (angle.coerceIn(0, 360) + WallpaperCatalog.ANGLE_STEP / 2) / WallpaperCatalog.ANGLE_STEP
    fun progressToAngle(progress: Int): Int = progress.coerceIn(0, ANGLE_STEPS) * WallpaperCatalog.ANGLE_STEP

    /**
     * Which theme the Wallpaper page edits: with separate wallpapers, the one the user picked
     * ([chosenLight], or the theme on screen until they pick); otherwise whatever is showing, since
     * the one wallpaper serves both.
     */
    fun editedLight(spec: AppearanceSpec, shownLight: Boolean, chosenLight: Boolean?): Boolean =
        if (spec.separateWallpapers) chosenLight ?: shownLight else shownLight

    /** Which screen's crop the editor shows: the only one the wallpaper is on, or the one picked for Both. */
    internal fun cropDevice(spec: AppearanceSpec, pickedCar: Boolean): WallDevice = when (spec.wallpaperTarget) {
        WallpaperTarget.PHONE -> WallDevice.PHONE
        WallpaperTarget.CAR -> WallDevice.CAR
        WallpaperTarget.BOTH -> if (pickedCar) WallDevice.CAR else WallDevice.PHONE
    }

    /** The shape of the car display the crop outline is drawn for (the 1020 x 604 head unit). */
    const val CAR_ASPECT = 1020f / 604f

    fun wallpaperOnPhone(spec: AppearanceSpec): Boolean = spec.wallpaperTarget != WallpaperTarget.CAR

    fun wallpaperOnCar(spec: AppearanceSpec): Boolean = spec.wallpaperTarget != WallpaperTarget.PHONE

    /** What the car draws with: this look, or the defaults when "Use this look on Android Auto" is off. */
    fun carSpec(spec: AppearanceSpec): AppearanceSpec = if (spec.carSameLook) spec else AppearanceSpec.DEFAULT

    /** Longest side a picked photo is kept at: a phone screen's height, so it never upscales. */
    const val PHOTO_MAX_SIDE = 1600

    /** [width] x [height] shrunk to fit [maxSide] on its longer side, aspect kept; never enlarged. */
    fun fitWithin(width: Int, height: Int, maxSide: Int = PHOTO_MAX_SIDE): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= maxSide || longest <= 0) return width to height
        val ratio = maxSide.toDouble() / longest
        return maxOf(1, (width * ratio).roundToInt()) to maxOf(1, (height * ratio).roundToInt())
    }

    // ------------------------------------------------------------------------------- schedule

    /** "18:30" for a minute of the day. */
    fun clock(minuteOfDay: Int): String {
        val m = ((minuteOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        return String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60)
    }

    private const val MINUTES_PER_DAY = 24 * 60
}
