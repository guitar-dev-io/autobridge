package dev.autobridge.design

import kotlin.math.min
import kotlin.math.pow

/**
 * Which part of an image or photo a screen shows: [zoom] percent (100 shows as much as fits) and
 * where in the leftover room the view sits, [x] and [y] in percent (50 is centred).
 */
data class WallpaperCrop(val zoom: Int = WallpaperCatalog.ZOOM_MIN, val x: Int = 50, val y: Int = 50) {
    /** The same crop with every value held to its range. */
    fun clamped() = WallpaperCrop(
        zoom.coerceIn(WallpaperCatalog.ZOOM_MIN, WallpaperCatalog.ZOOM_MAX), x.coerceIn(0, 100), y.coerceIn(0, 100)
    )

    companion object {
        val FULL = WallpaperCrop()
    }
}

/** Everything that makes one wallpaper look the way it does on one theme. */
data class WallpaperLook(
    val wallpaper: Wallpaper = Wallpaper.GLOW,
    val dim: Int = 20,
    val blur: Int = 0,
    val angle: Int = WallpaperCatalog.DEFAULT_ANGLE,
    val phoneCrop: WallpaperCrop = WallpaperCrop.FULL,
    val carCrop: WallpaperCrop = WallpaperCrop.FULL
)

/** A gradient as colours and their positions along the line, 0..1. */
class WallpaperRamp(val colors: IntArray, val stops: FloatArray)

/** The part of a source image, in its own pixels, that fills a view. */
data class WallpaperSource(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * The data behind the wallpapers: colours and gradients per theme, which settings each kind
 * answers to, the crop geometry and the text-contrast estimate. Pure Kotlin, so all of it is
 * unit-tested; the drawing lives in :app.
 */
object WallpaperCatalog {
    const val ZOOM_MIN = 100
    const val ZOOM_MAX = 300
    const val ZOOM_STEP = 5
    const val MAX_BLUR = 24
    const val BLUR_STEP = 2
    const val DEFAULT_ANGLE = 160
    const val ANGLE_STEP = 15

    /** Width over height of the drawn images, and of the sample ratio a photo is assumed to have. */
    const val IMAGE_ASPECT = 1.5f

    private class Pair2(val dark: Int, val light: Int)

    private val SOLID_TINTS = mapOf(
        Wallpaper.SOLID to Pair2(0xFF151C27.toInt(), 0xFFE3EAF4.toInt()),
        Wallpaper.SOLID_NAVY to Pair2(0xFF0E1A33.toInt(), 0xFFD6E6F8.toInt()),
        Wallpaper.SOLID_FOREST to Pair2(0xFF0D211B.toInt(), 0xFFD9EFE5.toInt()),
        Wallpaper.SOLID_PLUM to Pair2(0xFF1D1228.toInt(), 0xFFE6E0F5.toInt()),
        Wallpaper.SOLID_WINE to Pair2(0xFF2B1019.toInt(), 0xFFF6DEE4.toInt()),
        Wallpaper.SOLID_EMBER to Pair2(0xFF2B1A10.toInt(), 0xFFF1E6D2.toInt())
    )

    private class Ramp3(val dark: Array<Int>, val light: Array<Int>, val darkStops: FloatArray, val lightStops: FloatArray, val angle: Int)

    private val THREE = floatArrayOf(0f, 0.55f, 1f)
    private val GRADIENTS = mapOf(
        Wallpaper.NIGHT_ROAD to Ramp3(
            arrayOf(0xFF1B2A3F.toInt(), 0xFF121A26.toInt(), 0xFF0B0F15.toInt()),
            arrayOf(0xFFD6E2F0.toInt(), 0xFFF2F4F8.toInt(), 0xFFF2F4F8.toInt()),
            floatArrayOf(0f, 0.45f, 1f), floatArrayOf(0f, 0.65f, 1f), 180
        ),
        Wallpaper.GRADIENT_AURORA to Ramp3(arrayOf(0xFF0B3B3A.toInt(), 0xFF1B2A5C.toInt(), 0xFF3A1D5C.toInt()),
            arrayOf(0xFFCFEFE6.toInt(), 0xFFD9E3F8.toInt(), 0xFFEADCF6.toInt()), THREE, THREE, 160),
        Wallpaper.GRADIENT_SUNSET to Ramp3(arrayOf(0xFF2A1630.toInt(), 0xFF7A2F3F.toInt(), 0xFFD9733F.toInt()),
            arrayOf(0xFFFBE3D0.toInt(), 0xFFF6C9C0.toInt(), 0xFFE7B8D8.toInt()), THREE, THREE, 170),
        Wallpaper.GRADIENT_OCEAN to Ramp3(arrayOf(0xFF0A2A3A.toInt(), 0xFF0F5A66.toInt(), 0xFF1B8A8A.toInt()),
            arrayOf(0xFFD4EEF5.toInt(), 0xFFBDE3EA.toInt(), 0xFFA8D8D8.toInt()), THREE, THREE, 160),
        Wallpaper.GRADIENT_EMBER to Ramp3(arrayOf(0xFF2B1208.toInt(), 0xFF6B2A12.toInt(), 0xFFC2581D.toInt()),
            arrayOf(0xFFFBEAD2.toInt(), 0xFFF6D2A8.toInt(), 0xFFF0B98A.toInt()), THREE, THREE, 150),
        Wallpaper.GRADIENT_VIOLET to Ramp3(arrayOf(0xFF1B1140.toInt(), 0xFF3A1F7A.toInt(), 0xFF6B3FC0.toInt()),
            arrayOf(0xFFE6E0FA.toInt(), 0xFFD3C7F6.toInt(), 0xFFC0B0F0.toInt()), THREE, THREE, 160),
        Wallpaper.GRADIENT_MINT to Ramp3(arrayOf(0xFF0A2A22.toInt(), 0xFF0F4D3A.toInt(), 0xFF2A8A5C.toInt()),
            arrayOf(0xFFDDF3E8.toInt(), 0xFFC6EBD6.toInt(), 0xFFB0E0C4.toInt()), THREE, THREE, 160)
    )

    /** The tint of a plain-colour wallpaper on a [light] or dark theme; null for the others. */
    fun solidColor(wallpaper: Wallpaper, light: Boolean): Int? =
        SOLID_TINTS[wallpaper]?.let { if (light) it.light else it.dark }

    /** The colour ramp of a gradient wallpaper; null for the glow, which is drawn from the accent. */
    fun ramp(wallpaper: Wallpaper, light: Boolean): WallpaperRamp? = GRADIENTS[wallpaper]?.let {
        WallpaperRamp((if (light) it.light else it.dark).toIntArray(), if (light) it.lightStops else it.darkStops)
    }

    /** The angle a gradient starts at when picked. */
    fun defaultAngle(wallpaper: Wallpaper): Int = GRADIENTS[wallpaper]?.angle ?: DEFAULT_ANGLE

    /** Whether the angle slider does anything for [wallpaper]: every ramp turns, the glow does not. */
    fun turns(wallpaper: Wallpaper): Boolean = wallpaper in GRADIENTS

    /** Whether blur does anything: it softens images and photos, not flat colours or gradients. */
    fun blurs(wallpaper: Wallpaper): Boolean = wallpaper.kind == WallpaperKind.IMAGE || wallpaper.kind == WallpaperKind.PHOTO

    /** Images and photos can be cropped; the rest fill any screen on their own. */
    fun crops(wallpaper: Wallpaper): Boolean = blurs(wallpaper)

    /** What each tab of the picker offers, in order. */
    fun inKind(kind: WallpaperKind): List<Wallpaper> = when (kind) {
        WallpaperKind.SOLID -> listOf(Wallpaper.NONE, Wallpaper.LINES, Wallpaper.SOLID, Wallpaper.SOLID_NAVY,
            Wallpaper.SOLID_FOREST, Wallpaper.SOLID_PLUM, Wallpaper.SOLID_WINE, Wallpaper.SOLID_EMBER)
        WallpaperKind.GRADIENT -> listOf(Wallpaper.GLOW, Wallpaper.NIGHT_ROAD, Wallpaper.GRADIENT_AURORA,
            Wallpaper.GRADIENT_SUNSET, Wallpaper.GRADIENT_OCEAN, Wallpaper.GRADIENT_EMBER, Wallpaper.GRADIENT_VIOLET,
            Wallpaper.GRADIENT_MINT)
        WallpaperKind.IMAGE -> Wallpaper.entries.filter { it.kind == WallpaperKind.IMAGE }
        WallpaperKind.PHOTO -> listOf(Wallpaper.CUSTOM)
    }

    /** Which themes an image reads best on, as a hint on its tile. */
    enum class Suits { DARK, LIGHT, EITHER }

    fun suits(wallpaper: Wallpaper): Suits = when (wallpaper) {
        Wallpaper.IMAGE_AMBER_DUSK, Wallpaper.IMAGE_CALM_SEA -> Suits.EITHER
        Wallpaper.IMAGE_DUNES, Wallpaper.IMAGE_MORNING_MIST -> Suits.LIGHT
        else -> Suits.DARK
    }

    /** Relative luminance (0..1) of each drawn image: the mean of its sky, mid and ground colours. */
    private val IMAGE_LUMINANCE = mapOf(
        Wallpaper.IMAGE_MIDNIGHT_ROAD to 0.01, Wallpaper.IMAGE_BLUE_HOUR to 0.044, Wallpaper.IMAGE_AMBER_DUSK to 0.031,
        Wallpaper.IMAGE_AURORA to 0.006, Wallpaper.IMAGE_CITY_LIGHTS to 0.02, Wallpaper.IMAGE_CALM_SEA to 0.186,
        Wallpaper.IMAGE_DUNES to 0.63, Wallpaper.IMAGE_MORNING_MIST to 0.581
    )

    /**
     * How bright the wallpaper is, 0..1, for the contrast estimate. Plain wallpapers read it from
     * their colours; [backgroundLuminance] stands for the ones drawn over the theme background
     * (None, Lines, Glow) and [photoLuminance] for the user's photo.
     */
    fun luminance(wallpaper: Wallpaper, light: Boolean, backgroundLuminance: Double, photoLuminance: Double = 0.2): Double {
        solidColor(wallpaper, light)?.let { return luminanceOf(it) }
        GRADIENTS[wallpaper]?.let { g -> return (if (light) g.light else g.dark).map { luminanceOf(it) }.average() }
        IMAGE_LUMINANCE[wallpaper]?.let { return it }
        return if (wallpaper == Wallpaper.CUSTOM) photoLuminance else backgroundLuminance
    }

    /** WCAG relative luminance of an ARGB colour. */
    fun luminanceOf(argb: Int): Double {
        fun channel(shift: Int): Double {
            val v = ((argb shr shift) and 0xFF) / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /** Estimated contrast of the theme's text on a wallpaper of [luminance] under [dim] percent of black or white. */
    fun contrast(luminance: Double, dim: Int, light: Boolean): Double {
        val d = dim.coerceIn(0, AppearanceSpec.MAX_DIM) / 100.0
        return if (light) {
            val shown = luminance + (1 - luminance) * d
            (shown + 0.05) / (LIGHT_TEXT_LUMINANCE + 0.05)
        } else {
            (DARK_TEXT_LUMINANCE + 0.05) / (luminance * (1 - d) + 0.05)
        }
    }

    private const val LIGHT_TEXT_LUMINANCE = 0.0073
    private const val DARK_TEXT_LUMINANCE = 0.93

    enum class Legibility { EXCELLENT, GOOD, FAIR, LOW }

    fun legibility(ratio: Double): Legibility = when {
        ratio >= 7.0 -> Legibility.EXCELLENT
        ratio >= 4.5 -> Legibility.GOOD
        ratio >= 3.0 -> Legibility.FAIR
        else -> Legibility.LOW
    }

    // ----------------------------------------------------------------------------------- crop

    /**
     * The part of an image of [imageWidth] x [imageHeight] that fills a [viewWidth] x [viewHeight]
     * view under [crop]: the biggest area of the view's shape that fits, divided by the zoom, then
     * placed by [WallpaperCrop.x] and [WallpaperCrop.y] within the room that is left.
     */
    fun source(imageWidth: Float, imageHeight: Float, viewWidth: Float, viewHeight: Float, crop: WallpaperCrop): WallpaperSource {
        val c = crop.clamped()
        val zoom = c.zoom / 100f
        val fitW = min(imageWidth, imageHeight * viewWidth / viewHeight)
        val fitH = fitW * viewHeight / viewWidth
        val w = fitW / zoom
        val h = fitH / zoom
        return WallpaperSource((imageWidth - w) * c.x / 100f, (imageHeight - h) * c.y / 100f, w, h)
    }

    /** [source] as fractions of the image, for drawing the crop outline over the whole picture. */
    fun outline(imageAspect: Float, viewAspect: Float, crop: WallpaperCrop): WallpaperSource {
        val s = source(imageAspect, 1f, viewAspect, 1f, crop)
        return WallpaperSource(s.left / imageAspect, s.top, s.width / imageAspect, s.height)
    }

    /** Whether any room is left to move the crop sideways, and up and down, at this zoom. */
    fun canPanX(imageAspect: Float, viewAspect: Float, crop: WallpaperCrop): Boolean = outline(imageAspect, viewAspect, crop).width < 0.999f

    fun canPanY(imageAspect: Float, viewAspect: Float, crop: WallpaperCrop): Boolean = outline(imageAspect, viewAspect, crop).height < 0.999f
}
