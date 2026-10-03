package dev.autobridge.browser

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * The hardware render path for the car browser: a real window whose pixels land directly on the
 * Android Auto [Surface].
 *
 * ```
 * SurfaceContainer.surface (Android Auto)
 *        ▲  SurfaceFlinger composition (GPU / HWC) — no Canvas, no copy
 * VirtualDisplay(sink = car surface, OWN_CONTENT_ONLY | PRESENTATION)
 *        ▲
 * Presentation window (hardware accelerated)
 *   ├─ contentLayer
 *   │    ├─ WebView           ← Chromium GPU compositor, incl. inline <video>
 *   │    └─ fullscreen view   ← WebChromeClient.onShowCustomView (FullscreenVideoController)
 *   └─ chromeLayer            ← the renderer's existing toolbar / drawer / tabs / FAB drawing
 * ```
 *
 * Why this shows video when `WebView.draw(Canvas)` did not: the software draw only replays the
 * WebView's display list into a bitmap canvas, and Chromium's video frames never live in that
 * display list — they are separate GPU textures/surfaces composited later. Here nothing is replayed:
 * the WebView is attached to a real window, renders through its own hardware draw functor, and the
 * system compositor writes the finished frame (video included) into the car surface.
 *
 * Car App Library evidence for the mechanism: Android Auto hands an app a [Surface] only (no
 * [android.view.Display] id), and Fermata Auto's Car App Library route (`MirrorDisplay.java`) feeds
 * exactly that surface into `createVirtualDisplay(..., sc.getSurface(), ...)` and swaps it with
 * `vd.setSurface(...)` on surface churn. An own-content display needs no MediaProjection consent.
 *
 * Surface churn (template pushed over the browser, DHU disconnect) is handled with
 * [VirtualDisplay.setSurface] so the window — and the WebView in it — survive. A *size* change
 * recreates the whole window, because [Presentation] cancels itself whenever its display's metrics
 * change (`Presentation.handleDisplayChanged` → `isConfigurationStillValid`), so resizing the
 * display in place would dismiss it behind our back.
 *
 * Input does not come through this window: Android Auto delivers taps/scrolls to the
 * [androidx.car.app.SurfaceCallback], and [CarWebRenderer] injects them into the views directly.
 */
class CarHardwareWebWindow private constructor(
    private val display: VirtualDisplay,
    private val presentation: Presentation,
    /** Holds the page and, while a video is fullscreen, the custom view above it. */
    val contentLayer: FrameLayout,
    private val chromeLayer: View,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
) {
    /** Chromium fullscreen video host, the same controller the phone activities use. */
    val fullscreen = FullscreenVideoController(presentation.context, contentLayer, presentation.window)

    private var released = false

    /**
     * Where frames go while the car has its surface back; null while the car surface is attached.
     * See [setSurface] for why this exists rather than simply detaching.
     */
    private var parkedSink: ParkedSink? = null

    val displayId: Int get() = display.display.displayId

    /** Root view of the window, for display-id diagnostics. */
    val windowView: View? get() = presentation.window?.decorView

    fun matches(width: Int, height: Int, densityDpi: Int): Boolean =
        this.width == width && this.height == height && this.densityDpi == densityDpi

    /**
     * Points the display at a new car surface, or at a throwaway sink while the host has none.
     *
     * ## Why a sink and not `null`
     *
     * `display.surface = null` has "a similar effect to turning off the screen": the display goes
     * off and the [Presentation]'s window loses the surface its hardware renderer draws into. The
     * view tree does not know that. The WebView inside it is deliberately left running — that is the
     * whole point of keeping the window alive while the car shows something else, and
     * [BackgroundPlaybackMode] now stops the page pausing itself, so a playing page keeps producing
     * frames the entire time. The next frame reached a renderer with no surface and the platform
     * aborted the process outright:
     *
     * ```
     * Abort message: 'drawRenderNode called on a context with no surface!'
     * Fatal signal 6 (SIGABRT) in tid NNN (RenderThread)
     * ```
     *
     * Not an exception that could be caught — SIGABRT on the RenderThread takes the whole app down,
     * which is what was really happening behind "the music stopped when I left the browser" and
     * "YouTube started over when I came back": the app had died and restarted.
     *
     * So the display always has somewhere to draw. The sink is an [ImageReader] that throws every
     * frame away, which keeps the display on, the window's renderer valid, and the page playing. It
     * is created at the display's own size so no scaling question arises, and released as soon as a
     * real car surface is back.
     */
    fun setSurface(surface: Surface?) {
        if (surface != null) {
            display.surface = surface
            // Swapped first, so the display is never momentarily surface-less.
            parkedSink?.release()
            parkedSink = null
        } else {
            val sink = parkedSink ?: ParkedSink.create(width, height)?.also { parkedSink = it }
            // A sink that could not be created is still better than a crash: fall through to the
            // bare detach, which is only unsafe while something keeps drawing.
            display.surface = sink?.surface
        }
        AutoBridgeVideoLog.i(
            "virtual-display setSurface displayId=$displayId attached=${surface != null} " +
                "valid=${surface?.isValid} parked=${parkedSink != null}"
        )
    }

    /** Positions the page inside the display, in car-surface pixels. */
    fun placePage(page: View, left: Int, top: Int, pageWidth: Int, pageHeight: Int) {
        val params = FrameLayout.LayoutParams(pageWidth, pageHeight).apply {
            leftMargin = left
            topMargin = top
        }
        if (page.parent !== contentLayer) {
            (page.parent as? ViewGroup)?.removeView(page)
            contentLayer.addView(page, 0, params)
        } else {
            page.layoutParams = params
        }
    }

    /** Re-records the chrome layer only; the WebView keeps its own render node untouched. */
    fun invalidateChrome() {
        chromeLayer.invalidate()
    }

    /**
     * Tears the window down. The page view is removed first so the caller can re-host it in a
     * replacement window instead of destroying it. Safe to call twice.
     */
    fun release() {
        if (released) return
        released = true
        if (fullscreen.isShowing) fullscreen.hide()
        contentLayer.removeAllViews()
        runCatching { presentation.dismiss() }
            .onFailure { AutoBridgeVideoLog.w("presentation dismiss failed", it) }
        display.surface = null
        display.release()
        parkedSink?.release()
        parkedSink = null
        AutoBridgeVideoLog.i("hardware window released ${width}x$height")
    }

    /**
     * A surface that accepts frames and discards them, so a display with nowhere real to draw is
     * still a display that can be drawn to.
     *
     * The frames must actually be consumed: an [ImageReader] whose queue fills stops returning
     * buffers, and the producer blocked on it here would be the window's RenderThread. They are
     * drained on a thread of their own so a page playing video behind the car's own UI cannot add
     * per-frame work to the main thread.
     */
    private class ParkedSink private constructor(
        private val reader: ImageReader,
        private val thread: HandlerThread,
    ) {
        val surface: Surface get() = reader.surface

        fun release() {
            runCatching { reader.close() }
            runCatching { thread.quitSafely() }
        }

        companion object {
            /** Two is enough to keep the producer from blocking while one frame is being dropped. */
            private const val BUFFERS = 2

            /** Null when the platform refuses the reader; the caller treats that as "no sink". */
            fun create(width: Int, height: Int): ParkedSink? = runCatching {
                val thread = HandlerThread("ab-car-parked-sink").apply { start() }
                val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, BUFFERS)
                reader.setOnImageAvailableListener(
                    { r -> runCatching { r.acquireLatestImage()?.close() } },
                    Handler(thread.looper)
                )
                ParkedSink(reader, thread)
            }.onFailure { AutoBridgeVideoLog.w("parked sink unavailable", it) }.getOrNull()
        }
    }

    /** A view whose only job is to run the renderer's chrome drawing on the hardware canvas. */
    private class ChromeLayer(context: Context, private val drawChrome: (Canvas) -> Unit) : View(context) {
        init {
            isClickable = false
            isFocusable = false
            // Transparent everywhere chrome does not paint, so the page shows through.
            setWillNotDraw(false)
        }

        override fun onDraw(canvas: Canvas) {
            drawChrome(canvas)
        }
    }

    companion object {
        /**
         * Sanity floor only. The platform accepts any positive density, and the page density can
         * legitimately be low: desktop mode on an 800px panel wants 1280 CSS px, i.e. 100dpi.
         */
        private const val MIN_DENSITY_DPI = 60

        /** The density [create] will actually give the display for a requested [densityDpi]. */
        fun coerceDpi(densityDpi: Int): Int = densityDpi.coerceAtLeast(MIN_DENSITY_DPI)

        /**
         * Creates the display on [surface] and shows a window on it hosting [page].
         *
         * [densityDpi] is [BrowserViewport.pageDensityDpi], so the window (fullscreen video views
         * included) agrees with the page on one density. It does **not** set the page's CSS width:
         * Chromium reads that from the WebView's construction Context, which [PageDensityDisplay]
         * provides. Changing only this value was tried and left the trace at `pageScale=3.000`.
         *
         * Throws on failure; the caller logs it and falls back to [CarBrowserRenderMode.LEGACY_CANVAS].
         */
        fun create(
            context: Context,
            surface: Surface,
            width: Int,
            height: Int,
            densityDpi: Int,
            drawChrome: (Canvas) -> Unit,
            onUnexpectedDismiss: () -> Unit,
        ): CarHardwareWebWindow {
            require(width > 0 && height > 0) { "invalid car surface size ${width}x$height" }
            val displays = context.getSystemService(DisplayManager::class.java)
                ?: error("DisplayManager unavailable")
            val dpi = coerceDpi(densityDpi)
            val virtual = displays.createVirtualDisplay(
                "AutoBridgeCarBrowser", width, height, dpi, surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            ) ?: error("createVirtualDisplay returned null")
            try {
                val presentation = Presentation(context, virtual.display)
                val root = FrameLayout(presentation.context).apply {
                    setBackgroundColor(BrowserTheme.dark.background)
                }
                val content = FrameLayout(presentation.context)
                root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                val chrome = ChromeLayer(presentation.context, drawChrome)
                root.addView(chrome, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                presentation.window?.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
                presentation.setContentView(root)
                val window = CarHardwareWebWindow(virtual, presentation, content, chrome, width, height, dpi)
                presentation.setOnDismissListener {
                    if (!window.released) {
                        AutoBridgeVideoLog.w("hardware window dismissed by the platform; recreating")
                        onUnexpectedDismiss()
                    }
                }
                presentation.show()
                AutoBridgeVideoLog.i(
                    "hardware window created displayId=${virtual.display.displayId} ${width}x$height @${dpi}dpi"
                )
                return window
            } catch (error: RuntimeException) {
                virtual.release()
                throw error
            }
        }
    }
}
