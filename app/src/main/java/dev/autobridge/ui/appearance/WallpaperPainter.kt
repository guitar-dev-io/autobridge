package dev.autobridge.ui.appearance

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import dev.autobridge.design.ThemePalette
import dev.autobridge.design.ThemeResolver
import dev.autobridge.design.Wallpaper
import dev.autobridge.design.WallpaperCatalog
import dev.autobridge.design.WallpaperCrop
import dev.autobridge.design.WallpaperKind
import dev.autobridge.design.WallpaperLook
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Which screen a wallpaper is drawn for: each keeps its own crop of an image or photo. */
internal enum class WallDevice { PHONE, CAR }

/**
 * The one place a wallpaper is drawn: the phone Home, the car home, the live preview and the
 * option tiles all call it, so they cannot drift apart. It paints the theme background, then the
 * wallpaper (a tint, the glow, lines, a gradient at the chosen angle, a scene or the photo cropped
 * and blurred as asked) and last the dim.
 *
 * [density] converts the design's dp (line spacing, blur) to pixels.
 */
internal object WallpaperPainter {

    fun render(
        width: Int,
        height: Int,
        look: WallpaperLook,
        device: WallDevice,
        palette: ThemePalette,
        density: Float,
        photo: Bitmap? = null
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(max(1, width), max(1, height), Bitmap.Config.ARGB_8888)
        paintInto(Canvas(bitmap), bitmap.width.toFloat(), bitmap.height.toFloat(), look, device, palette, density, photo)
        return bitmap
    }

    fun paintInto(
        canvas: Canvas,
        w: Float,
        h: Float,
        look: WallpaperLook,
        device: WallDevice,
        palette: ThemePalette,
        density: Float,
        photo: Bitmap?
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.color = palette.background
        canvas.drawRect(0f, 0f, w, h, paint)
        val wallpaper = look.wallpaper
        when (wallpaper.kind) {
            WallpaperKind.SOLID -> when (wallpaper) {
                Wallpaper.NONE -> Unit
                Wallpaper.LINES -> lines(canvas, paint, w, h, palette, density)
                else -> WallpaperCatalog.solidColor(wallpaper, palette.isLight)?.let {
                    paint.color = it
                    canvas.drawRect(0f, 0f, w, h, paint)
                }
            }
            WallpaperKind.GRADIENT ->
                if (wallpaper == Wallpaper.GLOW) glow(canvas, paint, w, h, palette) else ramp(canvas, paint, w, h, look, palette)
            WallpaperKind.IMAGE -> {
                val scene = WallpaperScenes.bitmap(wallpaper, (max(w, h) * 1.5f).toInt().coerceIn(360, 1200))
                if (scene != null) image(canvas, paint, scene, w, h, crop(look, device), look.blur, density)
            }
            WallpaperKind.PHOTO ->
                if (photo != null) image(canvas, paint, photo, w, h, crop(look, device), look.blur, density) else placeholder(canvas, paint, w, h)
        }
        if (wallpaper != Wallpaper.NONE && look.dim > 0) {
            paint.shader = null
            paint.color = ThemeResolver.withAlpha(
                if (palette.isLight) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(),
                look.dim.coerceIn(0, 100) * 255 / 100
            )
            canvas.drawRect(0f, 0f, w, h, paint)
        }
    }

    private fun crop(look: WallpaperLook, device: WallDevice): WallpaperCrop =
        if (device == WallDevice.CAR) look.carCrop else look.phoneCrop

    /** radial-gradient(90% 45% at 90% 0%, accent 0x30, transparent 70%). */
    private fun glow(canvas: Canvas, paint: Paint, w: Float, h: Float, palette: ThemePalette) {
        val shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(ThemeResolver.withAlpha(palette.accent, 0x30), ThemeResolver.withAlpha(palette.accent, 0)),
            floatArrayOf(0f, 0.7f),
            Shader.TileMode.CLAMP
        )
        shader.setLocalMatrix(Matrix().apply { setScale(0.9f * w, 0.45f * h); postTranslate(0.9f * w, 0f) })
        paint.shader = shader
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    /** repeating-linear-gradient(135deg, hairline 0x80 1px, transparent to 16px). */
    private fun lines(canvas: Canvas, paint: Paint, w: Float, h: Float, palette: ThemePalette, density: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = ThemeResolver.withAlpha(palette.hairline, 0x80)
        val step = 16f * density * sqrt(2f)
        var c = step
        while (c < w + h) {
            canvas.drawLine(c, 0f, 0f, c, paint)
            c += step
        }
        paint.style = Paint.Style.FILL
    }

    /** CSS linear-gradient([angle]deg, ...): the line runs through the centre and is as long as the box needs. */
    private fun ramp(canvas: Canvas, paint: Paint, w: Float, h: Float, look: WallpaperLook, palette: ThemePalette) {
        val ramp = WallpaperCatalog.ramp(look.wallpaper, palette.isLight) ?: return
        val angle = Math.toRadians(look.angle.toDouble())
        val dx = sin(angle).toFloat()
        val dy = -cos(angle).toFloat()
        val half = (abs(w * dx) + abs(h * dy)) / 2f
        val cx = w / 2f
        val cy = h / 2f
        paint.shader = LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half, ramp.colors, ramp.stops, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    /** "Your photo" while there is no photo to show: the design's diagonal ramp. */
    private fun placeholder(canvas: Canvas, paint: Paint, w: Float, h: Float) {
        val angle = Math.toRadians(160.0)
        val dx = sin(angle).toFloat()
        val dy = -cos(angle).toFloat()
        val half = (abs(w * dx) + abs(h * dy)) / 2f
        paint.shader = LinearGradient(
            w / 2f - dx * half, h / 2f - dy * half, w / 2f + dx * half, h / 2f + dy * half,
            intArrayOf(0xFF3A4E63.toInt(), 0xFF1E2B39.toInt(), 0xFF0E141B.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    /**
     * [bitmap] cropped to the view as [crop] says. With [blur] it is drawn at a quarter of the size,
     * boxed-blurred there and scaled back up, which is soft enough and cheap enough to do once.
     */
    private fun image(canvas: Canvas, paint: Paint, bitmap: Bitmap, w: Float, h: Float, crop: WallpaperCrop, blur: Int, density: Float) {
        val s = WallpaperCatalog.source(bitmap.width.toFloat(), bitmap.height.toFloat(), w, h, crop)
        val src = Rect(Math.round(s.left), Math.round(s.top), Math.round(s.left + s.width), Math.round(s.top + s.height))
        if (blur <= 0) {
            canvas.drawBitmap(bitmap, src, RectF(0f, 0f, w, h), paint)
            return
        }
        val scale = 4
        val small = Bitmap.createBitmap(max(1, (w / scale).toInt()), max(1, (h / scale).toInt()), Bitmap.Config.ARGB_8888)
        Canvas(small).drawBitmap(bitmap, src, RectF(0f, 0f, small.width.toFloat(), small.height.toFloat()), paint)
        BoxBlur.blur(small, max(1, (blur * density / scale).toInt()))
        canvas.drawBitmap(small, null, RectF(0f, 0f, w, h), paint)
        small.recycle()
    }
}

/** Three passes of a running-sum box blur on every channel: close to a Gaussian, linear in the pixels. */
internal object BoxBlur {
    fun blur(bitmap: Bitmap, radius: Int) {
        if (radius < 1) return
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val scratch = IntArray(max(w, h))
        repeat(3) {
            for (y in 0 until h) pass(pixels, y * w, 1, w, radius, scratch)
            for (x in 0 until w) pass(pixels, x, w, h, radius, scratch)
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    /** One line of [count] pixels starting at [start], [step] apart, averaged over [radius] either side (edges repeat). */
    private fun pass(px: IntArray, start: Int, step: Int, count: Int, radius: Int, out: IntArray) {
        val window = 2 * radius + 1
        var a = 0; var r = 0; var g = 0; var b = 0
        fun at(i: Int) = px[start + i.coerceIn(0, count - 1) * step]
        for (i in -radius..radius) {
            val c = at(i)
            a += c ushr 24; r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
        }
        for (i in 0 until count) {
            out[i] = ((a / window) shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
            val add = at(i + radius + 1)
            val drop = at(i - radius)
            a += (add ushr 24) - (drop ushr 24)
            r += ((add shr 16) and 0xFF) - ((drop shr 16) and 0xFF)
            g += ((add shr 8) and 0xFF) - ((drop shr 8) and 0xFF)
            b += (add and 0xFF) - (drop and 0xFF)
        }
        for (i in 0 until count) px[start + i * step] = out[i]
    }
}

/**
 * A wallpaper as a Drawable, for the Home page, the option tiles and the previews. It is painted
 * once per size into a bitmap, so scrolling and redraws cost one blit.
 */
internal class WallpaperDrawable(
    private val look: WallpaperLook,
    private val device: WallDevice,
    private val palette: ThemePalette,
    private val density: Float,
    private val radiusPx: Float = 0f,
    private val photo: Bitmap? = null
) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val clip = Path()
    private var cached: Bitmap? = null

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val bitmap = cached?.takeIf { it.width == b.width() && it.height == b.height() }
            ?: WallpaperPainter.render(b.width(), b.height(), look, device, palette, density, photo).also {
                cached?.recycle()
                cached = it
            }
        if (radiusPx > 0f) {
            clip.reset()
            clip.addRoundRect(RectF(b), radiusPx, radiusPx, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawBitmap(bitmap, b.left.toFloat(), b.top.toFloat(), paint)
            canvas.restore()
        } else {
            canvas.drawBitmap(bitmap, b.left.toFloat(), b.top.toFloat(), paint)
        }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
