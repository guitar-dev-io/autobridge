package dev.autobridge.desktop.display

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Turns the Xvfb framebuffer into pictures on whatever is showing the browser: an Android Auto
 * [Surface], or a phone [DesktopView].
 *
 * The X screen is a large square; the browser window is resized to exactly the shape of the
 * target (see DesktopSession.fitTo), and only that [viewport] region is copied and drawn, scaled
 * to fill the target. Nothing is letterboxed.
 *
 * Event-driven: [requestFrame] on X DAMAGE, at most [MAX_FPS], plus a slow keep-alive redraw.
 *
 * Cost per changed frame: one copy of the viewport rows into a Bitmap and one upload. The R/B
 * swap (Xvfb is BGRX, Android RGBA) and opaque alpha are a color matrix, not a CPU loop.
 */
class FrameRenderer {
    /** Draws one frame on a car surface. The app supplies one that cannot abort the process. */
    fun interface SurfaceDrawer {
        fun draw(surface: Surface, block: (Canvas) -> Unit): Boolean
    }

    /** Draws over the page, in target pixels: the control rail, a notice. */
    fun interface Overlay {
        fun draw(canvas: Canvas, width: Int, height: Int)
    }

    private val thread = HandlerThread("desktop-frames").apply { start() }
    private val handler = Handler(thread.looper)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
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
    /** Size of the browser window inside the X screen; what is shown. */
    @Volatile var viewportWidth = 0
        private set
    @Volatile var viewportHeight = 0
        private set

    // Target: either a surface (car) or a view (phone).
    private var surface: Surface? = null
    private var surfaceDrawer: SurfaceDrawer = SurfaceDrawer { s, block ->
        // Safe default: the software canvas never takes the process down.
        val canvas = runCatching { s.lockCanvas(null) }.getOrNull() ?: return@SurfaceDrawer false
        try { block(canvas) } finally { runCatching { s.unlockCanvasAndPost(canvas) } }
        true
    }
    @Volatile private var view: DesktopView? = null
    @Volatile var targetWidth = 0
        private set
    @Volatile var targetHeight = 0
        private set
    private var area: Rect? = null

    @Volatile var overlay: Overlay? = null
    @Volatile var mapping: DisplayMapping? = null
        private set

    @Volatile var placeholderText: String = "…"
    /** Shown instead of the page, e.g. "Park to use the browser"; null shows the page. */
    @Volatile var blockedMessage: String? = null
        set(value) { field = value; requestFrame() }

    private var back: Bitmap? = null
    private var framePending = false
    private var lastFrameAt = 0L

    @Volatile var framesDrawn = 0L
        private set
    @Volatile var fps = 0f
        private set
    private var fpsWindowStart = 0L
    private var fpsWindowFrames = 0

    fun setSurfaceDrawer(drawer: SurfaceDrawer) = handler.post { surfaceDrawer = drawer }

    fun setViewport(width: Int, height: Int) = handler.post {
        viewportWidth = width
        viewportHeight = height
        updateMapping()
        drawNow()
    }

    fun attachSurface(target: Surface, width: Int, height: Int) = handler.post {
        view = null
        surface = target
        setTarget(width, height)
    }

    fun attachView(target: DesktopView, width: Int, height: Int) = handler.post {
        surface = null
        view = target
        setTarget(width, height)
    }

    /** The part of the target the page may use (car stable/visible area); null = all of it. */
    fun setArea(rect: Rect?) = handler.post {
        area = rect
        updateMapping()
        drawNow()
    }

    fun detach() {
        val done = CountDownLatch(1)
        handler.post {
            surface = null
            view = null
            mapping = null
            done.countDown()
        }
        done.await(1, TimeUnit.SECONDS)
    }

    /**
     * Detaches only if [v] is still the target, so a viewer that was paused after the car screen
     * took the renderer over does not blank the car's surface. Returns whether it detached.
     */
    fun detachView(v: DesktopView): Boolean {
        val done = CountDownLatch(1)
        var detached = false
        handler.post {
            if (view === v) {
                surface = null
                view = null
                mapping = null
                detached = true
            }
            done.countDown()
        }
        done.await(1, TimeUnit.SECONDS)
        return detached
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
            view = null
            back = null
            thread.quitSafely()
        }
    }

    private fun setTarget(width: Int, height: Int) {
        targetWidth = width
        targetHeight = height
        updateMapping()
        handler.removeCallbacks(keepAlive)
        handler.postDelayed(keepAlive, KEEP_ALIVE_MS)
        drawNow()
    }

    private fun updateMapping() {
        val a = area
        mapping = if (viewportWidth <= 0 || viewportHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) null
        else if (a == null) DisplayMapping(viewportWidth, viewportHeight, targetWidth, targetHeight)
        else DisplayMapping(viewportWidth, viewportHeight, targetWidth, targetHeight, a.left, a.top, a.right, a.bottom)
    }

    private val keepAlive: Runnable = object : Runnable {
        override fun run() {
            drawNow()
            if (surface != null || view != null) handler.postDelayed(this, KEEP_ALIVE_MS)
        }
    }

    /** Copies the viewport rows out of the framebuffer into a fresh or reused bitmap. */
    private fun copyFrame(fb: XwdFramebuffer): Bitmap? {
        val h = fb.header
        val rows = viewportHeight.coerceIn(1, h.height)
        if (h.bytesPerLine != h.width * 4) return null
        val bitmap = back?.takeIf { it.width == h.width && it.height == rows }
            ?: Bitmap.createBitmap(h.width, rows, Bitmap.Config.ARGB_8888).also { it.setHasAlpha(false) }
        val pixels = fb.pixels()
        pixels.limit(h.width * 4 * rows)
        bitmap.copyPixelsFromBuffer(pixels)
        return bitmap
    }

    private fun drawNow() {
        val s = surface
        val v = view
        if (s == null && v == null) return
        val fb = framebuffer
        val map = mapping
        val message = blockedMessage
        val frame = if (message == null && fb != null && map != null) copyFrame(fb) else null
        val src = Rect(0, 0, viewportWidth, viewportHeight)
        val dst = map?.let { RectF(it.offsetX, it.offsetY, it.offsetX + it.drawWidth, it.offsetY + it.drawHeight) }
        if (v != null) {
            // The view owns the bitmap from here; the next frame goes into a new one.
            back = null
            v.present(frame, src, dst, message ?: if (frame == null) placeholderText else null)
        } else if (s != null && s.isValid) {
            surfaceDrawer.draw(s) { canvas ->
                paintFrame(canvas, frame, src, dst, message ?: if (frame == null) placeholderText else null, targetWidth, targetHeight)
            }
            back = frame
        }
        lastFrameAt = SystemClock.uptimeMillis()
        framesDrawn++
        countFps(lastFrameAt)
    }

    /** Shared by the surface path and [DesktopView.onDraw]. */
    fun paintFrame(canvas: Canvas, frame: Bitmap?, src: Rect, dst: RectF?, message: String?, width: Int, height: Int) {
        canvas.drawColor(Color.BLACK)
        if (frame != null && dst != null) canvas.drawBitmap(frame, src, dst, paint)
        if (message != null) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = (height / 18f).coerceAtLeast(24f)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(message, width / 2f, height / 2f, p)
        }
        overlay?.draw(canvas, width, height)
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
