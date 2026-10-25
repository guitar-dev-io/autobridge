package dev.autobridge.ui.appearance

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.LruCache
import dev.autobridge.design.Wallpaper
import dev.autobridge.design.WallpaperCatalog
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * The eight wallpaper images, drawn in code: layered ridges, stars, a sun or moon, a road, a city,
 * a sea. They have no files to ship, are the same on every device and are the same picture at any
 * size, so the tile, the phone and the car all draw from the one scene. Every scene is laid out on
 * a 1200 x 800 sheet ([WallpaperCatalog.IMAGE_ASPECT]) and scaled to the bitmap asked for; the
 * seeds are fixed, so a scene never changes between runs.
 */
internal object WallpaperScenes {
    private const val SHEET_W = 1200f
    private const val SHEET_H = 800f

    private val cache = object : LruCache<String, Bitmap>(3) {}

    /** The scene for [wallpaper] [widthPx] wide, or null when [wallpaper] is not an image. */
    @Synchronized
    fun bitmap(wallpaper: Wallpaper, widthPx: Int): Bitmap? {
        if (wallpaper !in IMAGES) return null
        val width = widthPx.coerceIn(120, 1600)
        val key = "${wallpaper.name}@$width"
        cache.get(key)?.let { return it }
        val height = Math.round(width / WallpaperCatalog.IMAGE_ASPECT)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(width / SHEET_W, height / SHEET_H)
        Scene(canvas).draw(wallpaper)
        cache.put(key, bitmap)
        return bitmap
    }

    private val IMAGES = Wallpaper.entries.filter { it.kind == dev.autobridge.design.WallpaperKind.IMAGE }.toSet()

    private fun c(hex: Long) = hex.toInt() or 0xFF000000.toInt()
    private fun a(hex: Long, alpha: Float) = (hex.toInt() and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)

    private class Ridge(val base: Float, val amp: Float, val color: Long, val jag: Float = 1f)

    private class Scene(private val canvas: Canvas) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun draw(wallpaper: Wallpaper) {
            when (wallpaper) {
                Wallpaper.IMAGE_MIDNIGHT_ROAD -> midnightRoad()
                Wallpaper.IMAGE_BLUE_HOUR -> {
                    sky(longArrayOf(0x0b1a3d, 0x2a5c8f, 0xe9b48a), floatArrayOf(0f, 0.45f, 0.7f))
                    stars(Random(21), 40, 0.4f)
                    sun(300f, 400f, 26f, 0xfff1d6, 0xffd29a)
                    ridges(21, Ridge(0.50f, 0.06f, 0x5d86a8, 1.2f), Ridge(0.58f, 0.07f, 0x3b6486, 1.1f), Ridge(0.68f, 0.07f, 0x244766), Ridge(0.80f, 0.06f, 0x10263d, 0.9f))
                }
                Wallpaper.IMAGE_AMBER_DUSK -> {
                    sky(longArrayOf(0x241034, 0x8a2f4c, 0xf08a4a, 0xffc27a), floatArrayOf(0f, 0.38f, 0.62f, 0.75f))
                    sun(600f, 470f, 70f, 0xffe2a8, 0xffb25a)
                    ridges(31, Ridge(0.62f, 0.05f, 0x7a2f46), Ridge(0.69f, 0.06f, 0x4a1f3e), Ridge(0.78f, 0.05f, 0x2a1431, 0.9f), Ridge(0.88f, 0.04f, 0x140a1d, 0.8f))
                }
                Wallpaper.IMAGE_AURORA -> aurora()
                Wallpaper.IMAGE_CITY_LIGHTS -> city()
                Wallpaper.IMAGE_CALM_SEA -> sea()
                Wallpaper.IMAGE_DUNES -> dunes()
                Wallpaper.IMAGE_MORNING_MIST -> {
                    sky(longArrayOf(0xf3f8fd, 0xd6e6f5, 0xc4daef), floatArrayOf(0f, 0.6f, 1f))
                    sun(330f, 250f, 40f, 0xffffff, 0xffffff)
                    ridges(81, Ridge(0.52f, 0.05f, 0xc8d9ea), Ridge(0.60f, 0.06f, 0xaec6dd), Ridge(0.69f, 0.06f, 0x8fadc9, 0.9f), Ridge(0.79f, 0.06f, 0x6f90b0, 0.8f))
                }
                else -> Unit
            }
        }

        private fun sky(colors: LongArray, stops: FloatArray) {
            paint.shader = LinearGradient(0f, 0f, 0f, SHEET_H, IntArray(colors.size) { c(colors[it]) }, stops, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, SHEET_W, SHEET_H, paint)
            paint.shader = null
        }

        private fun stars(rng: Random, count: Int, reach: Float) {
            paint.color = 0xFFFFFFFF.toInt()
            repeat(count) {
                val x = rng.nextFloat() * SHEET_W
                val y = rng.nextFloat() * SHEET_H * reach
                val r = 0.6f + rng.nextFloat() * 1.4f
                paint.alpha = ((0.35f + rng.nextFloat() * 0.65f) * 0.9f * 255).toInt()
                canvas.drawCircle(x, y, r, paint)
            }
            paint.alpha = 255
        }

        private fun sun(x: Float, y: Float, r: Float, disc: Long, glow: Long) {
            paint.shader = RadialGradient(x, y, r * 5, intArrayOf(a(glow, 0.85f), a(glow, 0f)), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(x, y, r * 5, paint)
            paint.shader = null
            paint.color = c(disc)
            canvas.drawCircle(x, y, r, paint)
        }

        /** A ridge line: a few sines of falling size, the same every run for [seed]. */
        private fun ridgePath(rng: Random, ridge: Ridge): Path {
            val freqs = floatArrayOf(1.1f, 2.6f, 5.7f, 12.5f, 27f)
            val weight = floatArrayOf(1f, 0.5f, 0.24f * ridge.jag, 0.1f * ridge.jag, 0.04f * ridge.jag)
            val phase = FloatArray(5) { rng.nextFloat() * 6.283f }
            val path = Path().apply { moveTo(0f, SHEET_H) }
            var x = 0f
            while (x <= SHEET_W + 6f) {
                var y = ridge.base * SHEET_H
                for (i in 0 until 5) y += ridge.amp * SHEET_H * weight[i] * sin(x / SHEET_W * freqs[i] * 2f * PI.toFloat() + phase[i])
                path.lineTo(x, y)
                x += 6f
            }
            path.lineTo(SHEET_W, SHEET_H)
            path.close()
            return path
        }

        private fun ridges(seed: Int, vararg ridges: Ridge) {
            val rng = Random(seed)
            for (r in ridges) {
                paint.color = c(r.color)
                canvas.drawPath(ridgePath(rng, r), paint)
            }
        }

        private fun midnightRoad() {
            sky(longArrayOf(0x050b1f, 0x10264a, 0x1f5a73), floatArrayOf(0f, 0.5f, 0.62f))
            stars(Random(11), 140, 0.5f)
            sun(930f, 150f, 38f, 0xf3f6ff, 0x9fc4ff)
            ridges(11, Ridge(0.54f, 0.05f, 0x0f2a45), Ridge(0.585f, 0.04f, 0x0b1d33))
            val cone = Path().apply { moveTo(600f, 470f); lineTo(120f, 800f); lineTo(1080f, 800f); close() }
            paint.shader = LinearGradient(0f, 470f, 0f, 800f, a(0x9bd7ff, 0.35f), a(0x9bd7ff, 0f), Shader.TileMode.CLAMP)
            paint.alpha = 128
            canvas.drawPath(cone, paint)
            paint.shader = null
            paint.alpha = 255
            paint.color = c(0x0a1018)
            canvas.drawPath(Path().apply { moveTo(560f, 470f); lineTo(640f, 470f); lineTo(1180f, 800f); lineTo(20f, 800f); close() }, paint)
            // Centre dashes: they widen and space out toward the viewer, as in perspective.
            paint.color = c(0xf2d98a)
            paint.alpha = 217
            for (t in floatArrayOf(0.12f, 0.3f, 0.5f, 0.72f, 0.93f)) {
                val t2 = t + 0.07f
                if (t2 > 1f) continue
                val y1 = 470f + 330f * t * t
                val y2 = 470f + 330f * t2 * t2
                val w1 = 1.2f + 7f * t * t
                val w2 = 2f + 11f * t2 * t2
                canvas.drawPath(Path().apply { moveTo(600f - w1, y1); lineTo(600f + w1, y1); lineTo(600f + w2, y2); lineTo(600f - w2, y2); close() }, paint)
            }
            paint.alpha = 255
            paint.color = c(0x05080d)
            canvas.drawPath(Path().apply { moveTo(0f, 800f); lineTo(0f, 640f); lineTo(170f, 690f); lineTo(60f, 800f); close() }, paint)
            canvas.drawPath(Path().apply { moveTo(1200f, 800f); lineTo(1200f, 650f); lineTo(1040f, 700f); lineTo(1140f, 800f); close() }, paint)
        }

        private fun aurora() {
            sky(longArrayOf(0x030814, 0x0a2230, 0x0d3140), floatArrayOf(0f, 0.7f, 1f))
            stars(Random(41), 170, 0.5f)
            // Three ribbons: a wide soft stroke under a thin brighter one, both blurred.
            val ribbons = arrayOf(longArrayOf(0x3bffb0, 230, 70), longArrayOf(0x2fd6ff, 280, 55), longArrayOf(0xa07bff, 190, 50))
            val opacity = floatArrayOf(0.55f, 0.4f, 0.35f)
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            for (i in ribbons.indices) {
                val path = Path()
                var x = -40f
                while (x <= 1260f) {
                    val y = ribbons[i][1] + ribbons[i][2] * sin(x / 1200f * (2.2f + i * 0.7f) * 2f * PI.toFloat() + i)
                    if (x == -40f) path.moveTo(x, y) else path.lineTo(x, y)
                    x += 40f
                }
                paint.color = c(ribbons[i][0])
                paint.strokeWidth = (90 - i * 18).toFloat()
                paint.alpha = (opacity[i] * 255).toInt()
                paint.maskFilter = BlurMaskFilter(34f, BlurMaskFilter.Blur.NORMAL)
                canvas.drawPath(path, paint)
                paint.strokeWidth = 10f
                paint.alpha = (opacity[i] * 0.9f * 255).toInt()
                paint.maskFilter = BlurMaskFilter(9f, BlurMaskFilter.Blur.NORMAL)
                canvas.drawPath(path, paint)
            }
            paint.maskFilter = null
            paint.style = Paint.Style.FILL
            paint.alpha = 255
            ridges(41, Ridge(0.86f, 0.03f, 0x071820), Ridge(0.93f, 0.02f, 0x04080c, 0.8f))
            val rng = Random(42)
            paint.color = c(0x04080c)
            var x = -10f
            while (x < 1230f) {
                val th = 60f + rng.nextFloat() * 90f
                val base = 760f + rng.nextFloat() * 10f
                canvas.drawPath(Path().apply { moveTo(x - 17f, base); lineTo(x, base - th); lineTo(x + 17f, base); close() }, paint)
                x += 26f
            }
        }

        private fun city() {
            sky(longArrayOf(0x150d33, 0x5b2a72, 0xf08a5a), floatArrayOf(0f, 0.5f, 0.78f))
            paint.shader = RadialGradient(760f, 560f, 330f, intArrayOf(a(0xffb878, 0.8f), a(0xffb878, 0f)), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(760f, 560f, 330f, paint)
            paint.shader = null
            val rng = Random(51)
            val base = SHEET_H * 0.82f
            // Back row paler and sparsely lit, front row dark with more windows.
            data class Row(val color: Long, val lo: Float, val hi: Float, val wMin: Int, val wMax: Int, val lit: Float)
            for (row in listOf(Row(0x2b1b4a, 0.12f, 0.34f, 40, 90, 0.18f), Row(0x0c0818, 0.10f, 0.40f, 50, 110, 0.35f))) {
                var x = -20
                while (x < 1200) {
                    val w = rng.nextInt(row.wMin, row.wMax + 1)
                    val h = SHEET_H * (row.lo + rng.nextFloat() * (row.hi - row.lo))
                    val top = base - h
                    paint.color = c(row.color)
                    canvas.drawRect(x.toFloat(), top, (x + w).toFloat(), SHEET_H, paint)
                    if (rng.nextFloat() < 0.25f) canvas.drawRect(x + w / 2f - 2f, top - 26f, x + w / 2f + 2f, top, paint)
                    var wx = x + 8
                    while (wx < x + w - 10) {
                        var wy = top + 14f
                        while (wy < SHEET_H - 20f) {
                            if (rng.nextFloat() < row.lit) {
                                paint.color = c(0xffd98a)
                                paint.alpha = ((0.5f + rng.nextFloat() * 0.5f) * 255).toInt()
                                canvas.drawRect(wx.toFloat(), wy, wx + 7f, wy + 10f, paint)
                            }
                            wy += 22f
                        }
                        wx += 16
                    }
                    paint.alpha = 255
                    x += w + rng.nextInt(0, 7)
                }
            }
            paint.color = c(0x0c0818)
            canvas.drawRect(0f, base + 40f, SHEET_W, SHEET_H, paint)
        }

        private fun sea() {
            sky(longArrayOf(0x0e2a47, 0x6d6b95, 0xf4b183), floatArrayOf(0f, 0.5f, 0.8f))
            sun(600f, 440f, 54f, 0xfff0cf, 0xffbf80)
            paint.shader = LinearGradient(0f, 450f, 0f, 800f, intArrayOf(c(0xe49a6a), c(0x2a5b86), c(0x0a1f3c)), floatArrayOf(0f, 0.18f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 450f, SHEET_W, SHEET_H, paint)
            paint.shader = null
            val rng = Random(61)
            paint.color = c(0xffe3b5)
            for (i in 0 until 18) {
                val half = 120f * (1 - i / 22f)
                val x = 600f - 120f * (1 - i / 18f) - rng.nextInt(0, 40)
                paint.alpha = ((0.75f - i * 0.03f) * 255).toInt().coerceAtLeast(30)
                canvas.drawRoundRect(x, 470f + i * 15f, x + half * 2f * (0.4f + rng.nextFloat() * 0.6f), 474f + i * 15f, 2f, 2f, paint)
            }
            paint.alpha = 128
            paint.color = c(0xffd7a6)
            canvas.drawRect(0f, 449f, SHEET_W, 451f, paint)
            paint.alpha = 255
        }

        private fun dunes() {
            sky(longArrayOf(0xfff3dc, 0xfbd9a4, 0xf6bf84), floatArrayOf(0f, 0.55f, 1f))
            sun(860f, 230f, 46f, 0xfffaf0, 0xfff0c8)
            dune(470f, 34f, 0xf1cf9b, 0xd9a86a, 1)
            dune(560f, 42f, 0xe8bd82, 0xc58f55, 2)
            dune(660f, 50f, 0xdba66b, 0xb27a45, 3)
            dune(745f, 36f, 0xc98f52, 0x97642f, 4)
        }

        /** One sand ridge with its shaded lee side drawn first, a little lower. */
        private fun dune(base: Float, amp: Float, light: Long, shade: Long, seed: Int) {
            val phase = Random(seed).nextFloat() * 6f
            fun line(drop: Float) = Path().apply {
                moveTo(0f, SHEET_H)
                var x = 0f
                while (x <= SHEET_W + 10f) {
                    val y = base + amp * sin(x / SHEET_W * 2.3f * 2f * PI.toFloat() + phase) +
                        amp * 0.35f * sin(x / SHEET_W * 5.1f * 2f * PI.toFloat() + phase * 2f) + drop
                    lineTo(x, y)
                    x += 10f
                }
                lineTo(SHEET_W, SHEET_H)
                close()
            }
            paint.color = c(shade)
            paint.alpha = 128
            canvas.drawPath(line(26f + amp * 0.12f), paint)
            paint.alpha = 255
            paint.color = c(light)
            canvas.drawPath(line(0f), paint)
        }
    }
}
