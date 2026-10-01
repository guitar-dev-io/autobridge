package dev.autobridge.browser

import android.app.Presentation
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Gives a [View] a real, hardware-accelerated window without putting it on any screen.
 *
 * ## Why this exists
 *
 * [CarWebRenderer] draws its `WebView` onto the Android Auto surface itself, so the view belongs to
 * no Activity and, until this, to no window either. That costs far more than it looks like it
 * should. Chromium's `BrowserViewRenderer::ComputeTileRectAndUpdateMemoryPolicy` opens with
 *
 * ```
 * if (!hardware_enabled_) { compositor_->SetMemoryPolicy(0u); return gfx::Rect(); }
 * ```
 *
 * and `hardware_enabled_` is set in `OnDrawHardware()` alone — the draw pass a real window
 * performs. A view with no window therefore runs on a **zero-byte tile budget** for its whole life.
 * `cc` force-rasters the little a draw strictly requires and drops everything else, logging
 * `tile memory limits exceeded, some content may not draw` as it goes. A light page survives that;
 * a heavy one composites down to its root background colour, which on a dark-themed site is a
 * black panel. That is how this was found — m.youtube.com on the head unit, with 4,850 of those
 * lines in one five-minute session.
 *
 * Hosting the view in a [Presentation] on a [VirtualDisplay] this app owns is what restores the
 * budget: that window runs its own hardware draw pass, which sets `hardware_enabled_`, and
 * `WebSettings.setOffscreenPreRaster` (set by [CarWebRenderer] on the same WebView) then sizes the
 * budget from the view instead of from its on-screen visible rect, which is empty for a view
 * nobody can see. The two go together; neither works alone. Measured on device by
 * `OffscreenWebViewWindowTest`: detached 2 tile-memory errors, hosted 0, identical pixels.
 *
 * The renderer keeps drawing the view to the car surface itself, exactly as before. This window is
 * only ever a raster host — nothing it renders is shown to anyone, which is why its output goes to
 * an [ImageReader] that is drained and discarded. The cost is honest and known: the page rasters
 * twice, once for this window's GPU pass and once for the renderer's software draw. Removing the
 * second one means pointing this display straight at the car surface and rebuilding the toolbar,
 * drawer and tab switcher as real Views — worth doing, and much larger than this.
 *
 * While a view is hosted, **this class owns its layout params** — a hosted child is measured by
 * its parent, so the caller setting them itself both fights that pass and risks handing a
 * `FrameLayout` a params type it will crash casting. [resize] is the way to change the size.
 *
 * Needs no permission: an own-content virtual display is not a mirror, and a [Presentation] on a
 * display the app itself created does not need `SYSTEM_ALERT_WINDOW`. Its window type must be left
 * alone — `TYPE_PRESENTATION` is what the Presentation's own window context is created for, and
 * overriding it to `TYPE_APPLICATION_OVERLAY` is rejected as a window-type mismatch.
 */
class OffscreenWebViewWindow private constructor(
    private val display: VirtualDisplay,
    private val presentation: Presentation,
    private val sink: ImageReader,
    private val drainThread: HandlerThread,
    private val content: FrameLayout,
) {

    /**
     * Matches the hosted window to a new view size.
     *
     * The display is resized rather than recreated, because recreating it would detach the view —
     * and `OnDetachedFromWindow` calls `ReleaseHardware()`, which is exactly the zero budget this
     * class exists to avoid. A geometry change would otherwise silently undo the fix.
     */
    fun resize(width: Int, height: Int, densityDpi: Int) {
        if (width <= 0 || height <= 0) return
        runCatching { display.resize(width, height, densityDpi.coerceAtLeast(MIN_DENSITY_DPI)) }
            .onFailure { Log.w(TAG, "could not resize the raster host display", it) }
        // FrameLayout.LayoutParams, not the base class: FrameLayout casts its children's
        // params to MarginLayoutParams while measuring, so plain ViewGroup.LayoutParams throws.
        content.getChildAt(0)?.layoutParams = FrameLayout.LayoutParams(width, height)
    }

    /** Detaches the view and tears the window down. Safe to call twice. */
    fun release(): Unit = synchronized(this) {
        runCatching { content.removeAllViews() }
        runCatching { presentation.dismiss() }
        runCatching { display.release() }
        runCatching { sink.close() }
        runCatching { drainThread.quitSafely() }
    }

    companion object {
        private const val TAG = "AutoBridgeCarWeb"

        /** Android's own floor for a display; a resize below it is refused outright. */
        private const val MIN_DENSITY_DPI = 120

        /**
         * Buffers the raster host renders into. Two is the minimum that lets the producer keep
         * going while one is being retired, and nothing reads them, so more would only cost memory.
         */
        private const val SINK_BUFFERS = 2

        /**
         * Hosts [view] in an off-screen window sized [width] x [height], or returns null if the
         * platform refuses — in which case the caller keeps working exactly as it did before, on
         * the zero tile budget. A degraded page is better than no browser.
         *
         * [densityDpi] should be the density the view already believes it has. The point of this
         * window is to change the view's *raster budget* and nothing else; handing it a different
         * density would move the page's CSS width too, undoing the scale work in
         * [BrowserViewport.pageScalePercent].
         */
        fun attach(context: Context, view: View, width: Int, height: Int, densityDpi: Int): OffscreenWebViewWindow? {
            if (width <= 0 || height <= 0) return null
            val displays = context.getSystemService(DisplayManager::class.java) ?: return null
            var sink: ImageReader? = null
            var display: VirtualDisplay? = null
            var drainThread: HandlerThread? = null
            return try {
                val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, SINK_BUFFERS)
                sink = reader
                // Drained continuously, and that is not housekeeping: a producer whose buffers are
                // all outstanding stops rendering, and a raster host that has stopped rendering
                // stops producing tiles for rows the user is about to scroll to.
                val thread = HandlerThread("AutoBridgeRasterSink").apply { start() }
                drainThread = thread
                reader.setOnImageAvailableListener(
                    { ready -> runCatching { ready.acquireLatestImage() }.getOrNull()?.close() },
                    Handler(thread.looper)
                )
                val virtual = displays.createVirtualDisplay(
                    "AutoBridgeCarWebRaster", width, height,
                    densityDpi.coerceAtLeast(MIN_DENSITY_DPI), reader.surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                ) ?: error("no virtual display returned")
                display = virtual

                (view.parent as? ViewGroup)?.removeView(view)
                val root = FrameLayout(context)
                root.addView(view, FrameLayout.LayoutParams(width, height))
                val shown = Presentation(context, virtual.display).apply {
                    setContentView(root)
                    show()
                }
                Log.i(TAG, "Raster host window attached ${width}x$height @${densityDpi}dpi")
                OffscreenWebViewWindow(virtual, shown, reader, thread, root)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not host the car WebView in a window; tile budget stays zero", error)
                runCatching { display?.release() }
                runCatching { sink?.close() }
                runCatching { drainThread?.quitSafely() }
                null
            }
        }
    }
}
