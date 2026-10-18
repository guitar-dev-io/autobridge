package dev.autobridge.desktop.display

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import dev.autobridge.desktop.DesktopLog

/**
 * Draws the Xvfb framebuffer into one Android [Surface] — a phone SurfaceView or the Android Auto
 * surface; the renderer does not know which.
 *
 * Event-driven: [requestFrame] is called on X DAMAGE notifications, frames are paced to at most
 * [MAX_FPS], and a slow keep-alive redraw covers the case of a missed notification.
 *
 * Cost per changed frame (documented limitation): one copy of the framebuffer into a Bitmap
 * (`copyPixelsFromBuffer`, ~3.7 MB at 1280×720) and one texture upload by the hardware canvas.
 * The R/B swap (Xvfb is BGRX, Android RGBA) and forcing alpha opaque are done by a color matrix
 * on the GPU, not per pixel on the CPU. A zero-copy path would need an X server that renders into
 * AHardwareBuffers (the approach of Termux:X11, GPL-3.0 and therefore not linked into this MIT
 * app); see docs/desktop-browser/ARCHITECTURE.md.
 */
class SurfaceFrameRenderer {
    private val thread = HandlerThread("desktop-frames").apply { start() }
    private val handler = Handler(thread.looper)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    0f, 0f, 1f, 0f, 0f,
                    0f, 1f, 0f, 0f, 0f,
                    1f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 255f,
                )
            )
        )
    }

    @Volatile var framebuffer: XwdFramebuffer? = null
    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var area: IntArray? = null
    private var bitmap: Bitmap? = null
    private var framePending = false
    private var lastFrameAt = 0L
    @Volatile var mapping: DisplayMapping? = null
        private set

    @Volatile var framesDrawn = 0L
        private set
    @Volatile var fps = 0f
        private set
    private var fpsWindowStart = 0L
    private var fpsWindowFrames = 0

    /** Shown while there is no frame yet. */
    @Volatile var placeholderText: String = "…"

    /** Shown instead of the page, e.g. "Park to use the browser"; null shows the page. */
    @Volatile var blockedMessage: String? = null
        set(value) { field = value; requestFrame() }

    fun attach(surface: Surface, width: Int, height: Int) = handler.post {
        this.surface = surface
        surfaceWidth = width
        surfaceHeight = height
        updateMapping()
        drawNow()
    }

    fun setArea(left: Int, top: Int, right: Int, bottom: Int) = handler.post {
        area = intArrayOf(left, top, right, bottom)
        updateMapping()
        drawNow()
    }

    /** Stops drawing into the current surface; the browser keeps running. */
    fun detach() {
        val done = java.util.concurrent.CountDownLatch(1)
        handler.post {
            surface = null
            mapping = null
            done.countDown()
        }
        done.await(1, java.util.concurrent.TimeUnit.SECONDS)
    }

    fun requestFrame() {
        handler.post {
            if (framePending) return@post
            val wait = MIN_FRAME_MS - (SystemClock.uptimeMillis() - lastFrameAt)
            framePending = true
            handler.postDelayed({ framePending = false; drawNow() }, wait.coerceAtLeast(0))
        }
    }

    /** A few frames after user input: catches repaints whose DAMAGE arrives late or merged. */
    fun burst() {
        for (delay in longArrayOf(0, 120, 300, 700)) handler.postDelayed({ requestFrame() }, delay)
    }

    fun release() {
        handler.post {
            surface = null
            bitmap?.recycle()
            bitmap = null
            thread.quitSafely()
        }
    }

    private fun updateMapping() {
        val fb = framebuffer
        val a = area
        mapping = if (fb == null || surfaceWidth <= 0 || surfaceHeight <= 0) null else if (a == null) {
            DisplayMapping(fb.header.width, fb.header.height, surfaceWidth, surfaceHeight)
        } else {
            DisplayMapping(fb.header.width, fb.header.height, surfaceWidth, surfaceHeight, a[0], a[1], a[2], a[3])
        }
        handler.removeCallbacks(keepAlive)
        if (surface != null) handler.postDelayed(keepAlive, KEEP_ALIVE_MS)
    }

    private val keepAlive: Runnable = object : Runnable {
        override fun run() {
            drawNow()
            if (surface != null) handler.postDelayed(this, KEEP_ALIVE_MS)
        }
    }

    private fun drawNow() {
        val target = surface ?: return
        if (!target.isValid) return
        val fb = framebuffer
        if (fb != null && mapping == null) updateMapping()
        val map = mapping
        val canvas: Canvas = try {
            target.lockHardwareCanvas()
        } catch (e: Exception) {
            try { target.lockCanvas(null) } catch (e2: Exception) { DesktopLog.w("surface lock failed", e2); return }
        }
        try {
            canvas.drawColor(Color.BLACK)
            val message = blockedMessage
            if (message != null || fb == null || map == null) {
                drawMessage(canvas, message ?: placeholderText)
            } else {
                val header = fb.header
                val bmp = bitmap?.takeIf { it.width == header.width && it.height == header.height }
                    ?: Bitmap.createBitmap(header.width, header.height, Bitmap.Config.ARGB_8888).also {
                        bitmap?.recycle()
                        it.setHasAlpha(false)
                        bitmap = it
                    }
                bmp.copyPixelsFromBuffer(fb.pixels())
                canvas.save()
                canvas.translate(map.offsetX, map.offsetY)
                canvas.scale(map.scale, map.scale)
                canvas.drawBitmap(bmp, 0f, 0f, paint)
                canvas.restore()
            }
        } finally {
            runCatching { target.unlockCanvasAndPost(canvas) }
        }
        lastFrameAt = SystemClock.uptimeMillis()
        framesDrawn++
        countFps(lastFrameAt)
    }

    private fun drawMessage(canvas: Canvas, text: String) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (surfaceHeight / 18f).coerceAtLeast(24f)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(text, surfaceWidth / 2f, surfaceHeight / 2f, p)
    }

    private fun countFps(now: Long) {
        if (fpsWindowStart == 0L) fpsWindowStart = now
        fpsWindowFrames++
        val elapsed = now - fpsWindowStart
        if (elapsed >= 1000) {
            fps = fpsWindowFrames * 1000f / elapsed
            fpsWindowStart = now
            fpsWindowFrames = 0
        }
    }

    companion object {
        const val MAX_FPS = 30
        private const val MIN_FRAME_MS = 1000L / MAX_FPS
        private const val KEEP_ALIVE_MS = 2000L
    }
}
