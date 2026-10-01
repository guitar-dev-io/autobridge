package dev.autobridge.browser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.View
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device cover for [OffscreenWebViewWindow], which exists for one reason: a `WebView` in no
 * window gets a zero-byte tile budget from Chromium
 * (`if (!hardware_enabled_) compositor_->SetMemoryPolicy(0u)`, and `hardware_enabled_` is set in
 * `OnDrawHardware()` alone — the draw pass a real window performs). On the head unit that showed
 * up as m.youtube.com rendering as a flat black panel, with 4,850 `tile memory limits exceeded,
 * some content may not draw` lines in one session.
 *
 * The count itself cannot be asserted from here: it comes from the WebView's sandboxed renderer
 * process, whose logcat an app cannot read. What *can* be pinned is the condition that produces
 * it — whether the view is attached to a visible window — plus the thing most at risk of being
 * broken by moving the view into one: [CarWebRenderer] still draws that same view to the car
 * surface with an ordinary software canvas, and must still get pixels out of it.
 *
 * Measured while developing the fix, on the same page and device: detached 2 tile-memory errors,
 * hosted 0, with identical pixels. A third strategy, drawing the detached view through
 * `Surface.lockHardwareCanvas`, is deliberately not used and is covered by
 * [aHardwareCanvasIsNotASubstituteForAWindow] — it looks like the cheap fix and is a trap.
 *
 * Offline and deterministic: no network, no Android Auto host, no DHU (which cannot reach this
 * surface, and does not connect to current Android Auto builds — see `docs/DHU.md`).
 */
@RunWith(AndroidJUnit4::class)
class OffscreenWebViewWindowTest {

    private companion object {
        const val WIDTH = 800
        const val HEIGHT = 400
        const val FRAMES = 60
        const val FRAME_GAP_MS = 16L
        const val TIMEOUT_MS = 20_000L

        /**
         * A near-black body whose content cannot be served by a solid-colour draw quad — the
         * gradient and the text both have to be rastered into a tile, which is the budget that
         * was zero. Shaped like the page that exposed the fault.
         */
        val PAGE: String = buildString {
            append("<!doctype html><html><head>")
            append("<meta name=\"viewport\" content=\"width=device-width\">")
            append("</head><body style=\"margin:0;background:#0f0f0f\">")
            repeat(40) { i ->
                append(
                    "<div style=\"height:240px;will-change:transform;box-shadow:0 0 40px #fff;" +
                        "background:linear-gradient(${i * 7}deg,#ff00ff,#00ffff);" +
                        "font-size:48px;color:#ffffff\">RASTER $i</div>"
                )
            }
            append("</body></html>")
        }

        /** A pixel is page content when it is far from the near-black page background. */
        fun isContent(pixel: Int): Boolean =
            maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > 0x60
    }

    private val main = Handler(Looper.getMainLooper())
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun hostingPutsTheViewInAVisibleWindowAndReleasingTakesItBackOut() {
        val view = newWebView()
        var host: OffscreenWebViewWindow? = null
        try {
            onMain {
                assertFalse("a fresh WebView must start in no window", view.isAttachedToWindow)
            }
            val attached = requireNotNull(attach(view)) { "the platform refused to host the view" }
            host = attached

            onMain {
                // These two are precisely what Chromium reads to decide the tile budget: a view in
                // no window, or in an invisible one, is the zero-budget case this class removes.
                assertTrue("hosted view is not attached to a window", view.isAttachedToWindow)
                assertEquals(
                    "hosted view's window is not visible, so the budget stays zero",
                    View.VISIBLE, view.windowVisibility
                )
            }

            // A geometry change must resize the host, never re-create it: detaching the view runs
            // ReleaseHardware() and silently puts the budget back to zero.
            onMain { attached.resize(WIDTH / 2, HEIGHT / 2, 160) }
            onMain {
                assertTrue("resize detached the view", view.isAttachedToWindow)
            }

            onMain { attached.release() }
            host = null
            onMain { assertFalse("release left the view attached", view.isAttachedToWindow) }
        } finally {
            onMain {
                host?.release()
                view.destroy()
            }
        }
    }

    @Test
    fun aHostedViewStillRendersToTheSoftwareCanvasTheRendererDrawsWith() {
        val view = newWebView()
        var host: OffscreenWebViewWindow? = null
        withReader { reader, surface ->
            try {
                host = requireNotNull(attach(view)) { "the platform refused to host the view" }
                awaitLoaded(view)
                // The hosted window needs one draw pass of its own before it has rastered anything.
                Thread.sleep(1_000L)
                pump(view, surface) { canvas -> view.draw(canvas) }

                val content = contentFraction(reader)
                assertTrue(
                    "hosting the view broke CarWebRenderer's own draw: only" +
                        " ${"%.1f".format(content * 100)}% of the surface is page content",
                    content > 0.5f
                )
            } finally {
                onMain {
                    host?.release()
                    view.destroy()
                }
            }
        }
    }

    /**
     * `Surface.lockHardwareCanvas` looks like a one-line version of this whole class:
     * `AwContents.onDraw` picks its path from `Canvas.isHardwareAccelerated()` alone, so a hardware
     * canvas does reach `OnDrawHardware`. It does not work — the WebView's draw functor cannot
     * execute on a canvas belonging to no `ViewRootImpl`, so the page comes out as nothing but
     * `getEffectiveBackgroundColor()`. That is strictly worse than the bug being fixed: every site
     * would be a flat colour, not just the dark-themed ones.
     *
     * Pinned here so the next person to spot that shortcut sees the measurement instead of
     * spending an afternoon on it. If a future WebView makes it work, this test fails and says so.
     */
    @Test
    fun aHardwareCanvasIsNotASubstituteForAWindow() {
        val view = newWebView()
        withReader { reader, surface ->
            try {
                onMain {
                    view.measure(
                        View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
                    )
                    view.layout(0, 0, WIDTH, HEIGHT)
                }
                awaitLoaded(view)
                pump(view, surface, hardware = true) { canvas -> view.draw(canvas) }

                val content = contentFraction(reader)
                assertTrue(
                    "a hardware canvas now renders a detached WebView (${"%.1f".format(content * 100)}%" +
                        " content). OffscreenWebViewWindow may no longer be needed — re-measure" +
                        " the tile-memory errors before removing it.",
                    content < 0.1f
                )
            } finally {
                onMain { view.destroy() }
            }
        }
    }

    // ---------------------------------------------------------------- harness

    private fun attach(view: WebView): OffscreenWebViewWindow? {
        var host: OffscreenWebViewWindow? = null
        onMain {
            host = OffscreenWebViewWindow.attach(context, view, WIDTH, HEIGHT, 160)
        }
        return host
    }

    private fun newWebView(): WebView {
        var view: WebView? = null
        onMain {
            view = WebView(context).apply {
                BrowserDefaults.configure(context, this)
                settings.setOffscreenPreRaster(true)
                loadDataWithBaseURL(null, PAGE, "text/html", "utf-8", null)
            }
        }
        return requireNotNull(view)
    }

    private fun <T> withReader(block: (ImageReader, Surface) -> T): T {
        // GPU_COLOR_OUTPUT for a hardware canvas, CPU_WRITE_OFTEN for a software one, and
        // GPU_SAMPLED_IMAGE because Bitmap.wrapHardwareBuffer refuses a buffer without it.
        val reader = ImageReader.newInstance(
            WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                HardwareBuffer.USAGE_CPU_WRITE_OFTEN or
                HardwareBuffer.USAGE_CPU_READ_OFTEN
        )
        val surface = reader.surface
        return try {
            block(reader, surface)
        } finally {
            surface.release()
            reader.close()
        }
    }

    /** Repaints unconditionally, the way the renderer's frame pump does. */
    private fun pump(
        view: WebView,
        surface: Surface,
        hardware: Boolean = false,
        draw: (Canvas) -> Unit,
    ) {
        repeat(FRAMES) {
            onMain {
                val canvas: Canvas =
                    if (hardware) surface.lockHardwareCanvas() else surface.lockCanvas(null)
                try {
                    canvas.drawColor(Color.WHITE)
                    draw(canvas)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
            }
            Thread.sleep(FRAME_GAP_MS)
        }
    }

    /**
     * Fraction of the last posted frame that is page content.
     *
     * Read through [Bitmap.wrapHardwareBuffer] rather than `Image.getPlanes()`: a frame a hardware
     * canvas rendered lives in a GPU buffer, and on this device the plane mapping of one comes
     * back as zeroes — which reads as "the page did not draw" even with an opaque fill under it.
     */
    private fun contentFraction(reader: ImageReader): Float {
        val image = requireNotNull(reader.acquireLatestImage()) { "no frame was posted" }
        try {
            val buffer = requireNotNull(image.hardwareBuffer) { "frame had no hardware buffer" }
            try {
                val wrapped = requireNotNull(Bitmap.wrapHardwareBuffer(buffer, null))
                val pixels = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                wrapped.recycle()
                try {
                    var content = 0
                    val row = IntArray(pixels.width)
                    for (y in 0 until pixels.height) {
                        pixels.getPixels(row, 0, pixels.width, 0, y, pixels.width, 1)
                        content += row.count(::isContent)
                    }
                    return content.toFloat() / (pixels.width * pixels.height)
                } finally {
                    pixels.recycle()
                }
            } finally {
                buffer.close()
            }
        } finally {
            image.close()
        }
    }

    private fun awaitLoaded(view: WebView) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            var height = 0
            onMain { height = view.contentHeight }
            if (height > 0) return
            Thread.sleep(100L)
        }
        throw AssertionError("page never finished laying out")
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        main.post {
            try {
                block()
            } catch (error: Throwable) {
                failure = error
            } finally {
                latch.countDown()
            }
        }
        check(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "main thread did not run block" }
        failure?.let { throw it }
    }
}
