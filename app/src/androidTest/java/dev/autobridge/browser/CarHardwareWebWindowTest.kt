package dev.autobridge.browser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device cover for the one thing [CarHardwareWebWindow] must survive: the car taking its surface
 * back while the window keeps drawing.
 *
 * The window exists so the page — video and all — keeps living through a template pushed over the
 * browser, the rear camera on reverse, or the driver going back to Home. [CarWebRenderer.stop]
 * therefore does not pause a playing page, and [BackgroundPlaybackMode] stops the page pausing
 * itself, so frames keep coming the whole time the car surface is away. Detaching the display's
 * surface outright left those frames hitting a renderer with no surface, and the platform's answer
 * is not an exception:
 *
 * ```
 * Abort message: 'drawRenderNode called on a context with no surface!'
 * Fatal signal 6 (SIGABRT) in tid NNN (RenderThread)
 * ```
 *
 * SIGABRT on the RenderThread kills the process, so the symptom was never a stack trace: it was the
 * app disappearing and coming back empty, which on the head unit reads as "the music stopped" and
 * "YouTube started over". Three tombstones in one forty-minute DHU session.
 *
 * This test reproduces that pressure — a view that invalidates itself every frame, which is what a
 * playing page is — and asserts the process is still alive afterwards *and* that the window still
 * works. A test that only survived would pass against a window that quietly stopped drawing for
 * good, so frames after the re-attach are counted too.
 *
 * Offline and deterministic: no network, no Android Auto host, no DHU. The car surface is an
 * [ImageReader], exactly as the sibling [OffscreenWebViewWindowTest] does it.
 */
@RunWith(AndroidJUnit4::class)
class CarHardwareWebWindowTest {

    private companion object {
        const val WIDTH = 800
        const val HEIGHT = 400
        const val DPI = 160

        /** Long enough for many frames to be drawn with nowhere real to go. */
        const val DETACHED_MS = 2_500L
        const val SETTLE_MS = 1_200L
    }

    private val main = Handler(Looper.getMainLooper())
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun aPageThatKeepsDrawingSurvivesTheCarTakingItsSurfaceBack() {
        val drain = HandlerThread("test-reader-drain").apply { start() }
        val counting = AtomicBoolean(false)
        val framesWhileAttached = AtomicInteger()
        val reader = ImageReader.newInstance(
            WIDTH, HEIGHT, PixelFormat.RGBA_8888, 4,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_CPU_READ_OFTEN
        )
        // Drained continuously: a full queue would block the display's producer, which is the very
        // RenderThread whose behaviour is under test.
        reader.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireLatestImage() }.getOrNull()
            if (image != null) {
                image.close()
                if (counting.get()) framesWhileAttached.incrementAndGet()
            }
        }, Handler(drain.looper))

        val surface = reader.surface
        var window: CarHardwareWebWindow? = null
        try {
            val page = Pulsing(context)
            onMain {
                window = CarHardwareWebWindow.create(
                    context = context,
                    surface = surface,
                    width = WIDTH,
                    height = HEIGHT,
                    densityDpi = DPI,
                    drawChrome = { canvas -> canvas.drawColor(Color.TRANSPARENT) },
                    onUnexpectedDismiss = {},
                )
                window!!.placePage(page, 0, 0, WIDTH, HEIGHT)
            }
            val live = requireNotNull(window)
            Thread.sleep(SETTLE_MS)

            // The car takes its surface: from here every frame the page draws has nowhere real to go.
            onMain { live.setSurface(null) }
            val drawsBefore = page.draws()
            Thread.sleep(DETACHED_MS)
            val drawsWhileDetached = page.draws() - drawsBefore
            assertTrue(
                "the page stopped drawing while detached, so this never tested the crash path",
                drawsWhileDetached > 10
            )

            // Still here. Under the bare detach the process would have aborted above.
            counting.set(true)
            onMain {
                live.setSurface(surface)
                live.invalidateChrome()
            }
            Thread.sleep(SETTLE_MS)
            assertTrue(
                "no frame reached the car surface after it came back: the window survived but " +
                    "stopped drawing, which is no better on the head unit",
                framesWhileAttached.get() > 0
            )
        } finally {
            onMain { window?.release() }
            reader.close()
            drain.quitSafely()
        }
    }

    /** A view that redraws forever, the way a page playing video does. */
    private class Pulsing(context: Context) : View(context) {
        private val drawn = AtomicInteger()

        fun draws(): Int = drawn.get()

        override fun onDraw(canvas: Canvas) {
            val n = drawn.incrementAndGet()
            canvas.drawColor(if (n % 2 == 0) Color.RED else Color.GREEN)
            // Keeps the window under continuous draw pressure without a Choreographer of our own.
            invalidate()
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val latch = java.util.concurrent.CountDownLatch(1)
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
        assertTrue("main thread did not run the block", latch.await(20, java.util.concurrent.TimeUnit.SECONDS))
        failure?.let { throw it }
    }
}
