package dev.autobridge.ui.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import dev.autobridge.design.ThemePalette
import dev.autobridge.design.ThemeResolver
import dev.autobridge.design.Wallpaper
import java.io.File

/**
 * The phone Home's wallpaper, the redesign's six options drawn natively: the theme background
 * with an accent glow in the top-right corner, diagonal hairlines, the night-road gradient, a
 * solid tint, or the user's photo (centre-cropped), then the dim on top.
 *
 * [density] converts the design's CSS pixels (dp) for the line spacing.
 */
class WallpaperDrawable(
    private val wallpaper: Wallpaper,
    private val palette: ThemePalette,
    private val dimColor: Int,
    private val density: Float,
    private val photo: Bitmap? = null
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        paint.shader = null
        paint.color = palette.background
        canvas.drawRect(b, paint)
        when (wallpaper) {
            Wallpaper.NONE -> Unit
            Wallpaper.GLOW -> {
                // CSS: radial-gradient(90% 45% at 90% 0%, accent@0x30, transparent 70%).
                val shader = RadialGradient(
                    0f, 0f, 1f,
                    intArrayOf(ThemeResolver.withAlpha(palette.accent, 0x30), ThemeResolver.withAlpha(palette.accent, 0)),
                    floatArrayOf(0f, 0.7f),
                    Shader.TileMode.CLAMP
                )
                shader.setLocalMatrix(Matrix().apply {
                    setScale(w * 0.9f, h * 0.45f)
                    postTranslate(b.left + w * 0.9f, b.top.toFloat())
                })
                paint.shader = shader
                canvas.drawRect(b, paint)
            }
            Wallpaper.LINES -> {
                // CSS: repeating-linear-gradient(135deg, hairline@0x80 1px every 16px).
                paint.color = ThemeResolver.withAlpha(palette.hairline, 0x80)
                paint.strokeWidth = density
                val step = 16f * density * Math.sqrt(2.0).toFloat()
                canvas.save()
                canvas.clipRect(b)
                var x = b.left - h
                while (x < b.right + h) {
                    canvas.drawLine(x, b.bottom.toFloat(), x + h, b.top.toFloat(), paint)
                    x += step
                }
                canvas.restore()
            }
            Wallpaper.NIGHT_ROAD -> {
                paint.shader = if (palette.isLight) {
                    LinearGradient(0f, b.top.toFloat(), 0f, b.top + h, intArrayOf(0xFFD6E2F0.toInt(), 0xFFF2F4F8.toInt()),
                        floatArrayOf(0f, 0.65f), Shader.TileMode.CLAMP)
                } else {
                    LinearGradient(0f, b.top.toFloat(), 0f, b.top + h,
                        intArrayOf(0xFF1B2A3F.toInt(), 0xFF121A26.toInt(), 0xFF0B0F15.toInt()),
                        floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
                }
                canvas.drawRect(b, paint)
            }
            Wallpaper.SOLID -> {
                paint.color = if (palette.isLight) 0xFFE3EAF4.toInt() else 0xFF151C27.toInt()
                canvas.drawRect(b, paint)
            }
            Wallpaper.CUSTOM -> photo?.let { drawCropped(canvas, it, b) }
        }
        if (dimColor ushr 24 != 0) {
            paint.shader = null
            paint.color = dimColor
            canvas.drawRect(b, paint)
        }
    }

    private fun drawCropped(canvas: Canvas, bitmap: Bitmap, b: Rect) {
        val scale = maxOf(b.width() / bitmap.width.toFloat(), b.height() / bitmap.height.toFloat())
        val srcW = (b.width() / scale).toInt()
        val srcH = (b.height() / scale).toInt()
        val left = (bitmap.width - srcW) / 2
        val top = (bitmap.height - srcH) / 2
        paint.shader = null
        canvas.drawBitmap(bitmap, Rect(left, top, left + srcW, top + srcH), b, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    companion object {
        /**
         * Reads the picked photo at about [targetWidth] pixels wide; null when it is missing or
         * unreadable, in which case Home falls back to no wallpaper. Call off the main thread.
         */
        fun decodePhoto(file: File, targetWidth: Int): Bitmap? = runCatching {
            if (!file.isFile) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }
}
