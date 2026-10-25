package dev.autobridge.ui.appearance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.net.Uri
import dev.autobridge.design.ThemePalette
import dev.autobridge.design.ThemeResolver
import dev.autobridge.design.Wallpaper
import dev.autobridge.settings.AppearanceStore
import java.io.File
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A wallpaper option drawn the way the redesign prototype draws it (its CSS backgrounds, in
 * Canvas), plus an optional dim layer. Used for the option tiles, the live preview's mini phone
 * and mini car, and the card-style samples.
 *
 * [photo] is the saved custom wallpaper, centre-cropped; without one, Your photo shows the
 * prototype's placeholder gradient.
 */
class WallpaperDrawable(
    private val wallpaper: Wallpaper,
    private val palette: ThemePalette,
    private val density: Float,
    private val radiusPx: Float = 0f,
    /** 0..70: share of black (white in light mode) laid over the art. */
    private val dimPercent: Int = 0,
    private val photo: Bitmap? = null
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clip = Path()
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        if (radiusPx > 0f) {
            clip.reset()
            rect.set(0f, 0f, w, h)
            clip.addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW)
            canvas.clipPath(clip)
        }
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = palette.background
        canvas.drawRect(0f, 0f, w, h, paint)
        when (wallpaper) {
            Wallpaper.NONE -> Unit
            Wallpaper.GLOW -> drawGlow(canvas, w, h)
            Wallpaper.LINES -> drawLines(canvas, w, h)
            Wallpaper.NIGHT_ROAD -> drawNightRoad(canvas, w, h)
            Wallpaper.SOLID -> {
                paint.color = if (palette.isLight) 0xFFE3EAF4.toInt() else 0xFF151C27.toInt()
                canvas.drawRect(0f, 0f, w, h, paint)
            }
            Wallpaper.CUSTOM -> if (photo != null) drawPhoto(canvas, photo, w, h) else drawPlaceholder(canvas, w, h)
        }
        if (dimPercent > 0) {
            paint.shader = null
            paint.color = ThemeResolver.withAlpha(
                if (palette.isLight) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(),
                dimPercent * 255 / 100
            )
            canvas.drawRect(0f, 0f, w, h, paint)
        }
        canvas.restore()
    }

    /** radial-gradient(90% 45% at 90% 0%, accent 0x30, transparent 70%). */
    private fun drawGlow(canvas: Canvas, w: Float, h: Float) {
        val accent = palette.accent
        val shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(ThemeResolver.withAlpha(accent, 0x30), ThemeResolver.withAlpha(accent, 0)),
            floatArrayOf(0f, 0.7f),
            Shader.TileMode.CLAMP
        )
        shader.setLocalMatrix(Matrix().apply { setScale(0.9f * w, 0.45f * h); postTranslate(0.9f * w, 0f) })
        paint.shader = shader
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    /** repeating-linear-gradient(135deg, hairline 0x80 1px, transparent to 16px). */
    private fun drawLines(canvas: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = ThemeResolver.withAlpha(palette.hairline, 0x80)
        // Lines where (x + y) / sqrt(2) is a multiple of 16 px: "/" strokes, 16 px apart.
        val step = 16f * density * sqrt(2f)
        var c = step
        while (c < w + h) {
            canvas.drawLine(c, 0f, 0f, c, paint)
            c += step
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawNightRoad(canvas: Canvas, w: Float, h: Float) {
        paint.shader = if (palette.isLight) {
            LinearGradient(0f, 0f, 0f, h, intArrayOf(0xFFD6E2F0.toInt(), 0xFFF2F4F8.toInt()), floatArrayOf(0f, 0.65f), Shader.TileMode.CLAMP)
        } else {
            LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(0xFF1B2A3F.toInt(), 0xFF121A26.toInt(), 0xFF0B0F15.toInt()),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    /** linear-gradient(160deg, #3A4E63, #1E2B39 55%, #0E141B). */
    private fun drawPlaceholder(canvas: Canvas, w: Float, h: Float) {
        val angle = Math.toRadians(160.0)
        val dx = sin(angle).toFloat()
        val dy = -cos(angle).toFloat()
        val half = (kotlin.math.abs(w * dx) + kotlin.math.abs(h * dy)) / 2f
        val cx = w / 2f
        val cy = h / 2f
        paint.shader = LinearGradient(
            cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half,
            intArrayOf(0xFF3A4E63.toInt(), 0xFF1E2B39.toInt(), 0xFF0E141B.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    private fun drawPhoto(canvas: Canvas, bitmap: Bitmap, w: Float, h: Float) {
        val scale = max(w / bitmap.width, h / bitmap.height)
        val m = Matrix().apply {
            setScale(scale, scale)
            postTranslate((w - bitmap.width * scale) / 2f, (h - bitmap.height * scale) / 2f)
        }
        canvas.drawBitmap(bitmap, m, paint)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/**
 * The custom wallpaper photo: picked once through the Android photo picker, saved downscaled as a
 * JPEG at [AppearanceStore.customWallpaperFile], and read back small for the previews.
 */
object WallpaperPhoto {
    private const val PREVIEW_SIDE = 480
    private const val JPEG_QUALITY = 85

    @Volatile
    private var cached: Pair<Long, Bitmap>? = null

    /** The saved photo, decoded small enough for a preview tile, or null when there is none. */
    fun preview(context: Context): Bitmap? {
        val file = AppearanceStore.customWallpaperFile(context)
        if (!file.isFile) return null
        val stamp = file.lastModified() xor file.length()
        cached?.let { (key, bitmap) -> if (key == stamp) return bitmap }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PREVIEW_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        cached = stamp to bitmap
        return bitmap
    }

    /**
     * Decodes [uri] (EXIF rotation applied by [ImageDecoder]), shrinks it to
     * [AppearanceModel.PHOTO_MAX_SIDE] and writes it over the saved photo. Blocking: call it off
     * the main thread. Returns false when the image could not be read.
     */
    fun save(context: Context, uri: Uri): Boolean = runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (w, h) = AppearanceModel.fitWithin(info.size.width, info.size.height)
            decoder.setTargetSize(w, h)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val target = AppearanceStore.customWallpaperFile(context)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        bitmap.recycle()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        cached = null
        true
    }.getOrDefault(false)
}
