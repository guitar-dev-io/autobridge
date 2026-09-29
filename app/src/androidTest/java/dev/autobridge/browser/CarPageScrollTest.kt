package dev.autobridge.browser

import android.graphics.SurfaceTexture
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device cover for the page scroll bounds.
 *
 * `View.scrollTo` does not clamp and `WebView` does not clamp on its behalf, so an unclamped
 * `scrollBy` let the offset run past the content and stick there — seen on a head unit as
 * `viewScroll=-279,255`, which froze scrolling and drew the page shifted sideways. The arithmetic
 * is covered on the JVM; what needs a real WebView is that the renderer reads the *actual* scroll
 * extents and that no scroll path can leave them.
 *
 * This cannot be driven from the Desktop Head Unit: its command set has only `tap`, and injecting
 * into the host's virtual displays never reaches the custom surface (see `docs/DHU_SCENARIOS.md`).
 * Calling the renderer directly is the only repeatable way to test it.
 */
@RunWith(AndroidJUnit4::class)
class CarPageScrollTest {

    private companion object {
        /** A car surface roughly the size of a 1080p head unit's content area. */
        const val SURFACE_WIDTH = 1816
        const val SURFACE_HEIGHT = 1056
        const val SURFACE_DPI = 160

        /** Far larger than any page, so an unclamped scroll would be unmistakable. */
        const val HUGE = 1_000_000f

        const val LOAD_TIMEOUT_MS = 30_000L
        const val POLL_MS = 250L
    }

    private lateinit var renderer: CarWebRenderer
    private lateinit var surfaceTexture: SurfaceTexture
    private lateinit var surface: Surface
    private val main = Handler(Looper.getMainLooper())

    @Before
    fun setUp() {
        onMain {
            renderer = CarWebRenderer(ApplicationProvider.getApplicationContext())
            surfaceTexture = SurfaceTexture(0).apply {
                setDefaultBufferSize(SURFACE_WIDTH, SURFACE_HEIGHT)
            }
            surface = Surface(surfaceTexture)
        }
    }

    @After
    fun tearDown() {
        onMain { renderer.destroy() }
        surface.release()
        surfaceTexture.release()
    }

    @Test
    fun pageScrollNeverLeavesTheContentWhenThereIsNothingToScroll() {
        start("https://example.com")
        awaitLaidOut()

        // A page that fits has no scroll range at all; every direction must be refused.
        trace("short page loaded")
        val bounds = requireNotNull(scrollState())
        assumeTrue("page unexpectedly scrollable, see the other test", bounds.maxY == 0)

        scroll(0f, HUGE)
        scroll(0f, -HUGE)
        scroll(HUGE, 0f)
        scroll(-HUGE, 0f)

        val after = requireNotNull(scrollState())
        assertEquals("horizontal offset moved on an unscrollable page", 0, after.x)
        assertEquals("vertical offset moved on an unscrollable page", 0, after.y)
    }

    @Test
    fun pageScrollStopsAtBothEndsInsteadOfRunningPastTheContent() {
        start("https://en.m.wikipedia.org/wiki/Android_Auto")
        awaitLaidOut()
        val scrollable = awaitScrollable()
        trace("long page loaded, scrollable=$scrollable")
        assumeTrue("page never became scrollable (offline?)", scrollable)

        // Drive far past the end, then far past the beginning. Both must land exactly on a bound,
        // which is the property the head unit lost: the offset used to overshoot and stay there.
        scroll(0f, HUGE)
        trace("after +HUGE vertical")
        val atEnd = requireNotNull(scrollState())
        assertEquals("vertical offset ran past the content end", atEnd.maxY, atEnd.y)

        scroll(0f, -HUGE)
        val atStart = requireNotNull(scrollState())
        assertEquals("vertical offset ran past the content start", 0, atStart.y)

        // Horizontal is the axis the head unit actually reported as negative.
        scroll(-HUGE, 0f)
        trace("after -HUGE horizontal")
        val atLeft = requireNotNull(scrollState())
        assertEquals("horizontal offset went negative", 0, atLeft.x)

        scroll(HUGE, 0f)
        val atRight = requireNotNull(scrollState())
        assertEquals("horizontal offset ran past the content edge", atRight.maxX, atRight.x)
    }

    @Test
    fun everyScrollLeavesTheOffsetInsideItsOwnReportedBounds() {
        start("https://en.m.wikipedia.org/wiki/Android_Auto")
        awaitLaidOut()
        assumeTrue("page never became scrollable (offline?)", awaitScrollable())

        // A sweep of ordinary and absurd deltas, mixed directions. The invariant is not "the page
        // moved" but "wherever it ended up is inside the content", which is what a clamp means.
        val deltas = listOf(120f, -40f, 5_000f, -1f, HUGE, -HUGE, 900f, -350f, 0f)
        for (dy in deltas) {
            for (dx in deltas) {
                scroll(dx, dy)
                val s = requireNotNull(scrollState())
                assertTrue(
                    "x=${s.x} outside 0..${s.maxX} after dx=$dx dy=$dy",
                    s.x in 0..s.maxX
                )
                assertTrue(
                    "y=${s.y} outside 0..${s.maxY} after dx=$dx dy=$dy",
                    s.y in 0..s.maxY
                )
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun start(url: String) = onMain {
        renderer.start(surface, SURFACE_WIDTH, SURFACE_HEIGHT, SURFACE_DPI, url)
    }

    private fun scroll(dx: Float, dy: Float) {
        onMain { renderer.scrollBy(dx, dy) }
        // scrollBy posts to the main thread; drain it so the read below sees the result.
        onMain { }
    }

    private fun trace(label: String) {
        Log.i("CarPageScrollTest", "$label -> ${scrollState()}")
    }

    private fun scrollState(): CarWebRenderer.PageScrollState? {
        var state: CarWebRenderer.PageScrollState? = null
        onMain { state = renderer.pageScrollState() }
        return state
    }

    /** Waits until the renderer has a WebView laid out against the surface. */
    private fun awaitLaidOut() = await { scrollState() != null }

    /** Waits until the loaded page is actually taller than the viewport. */
    private fun awaitScrollable(): Boolean = await { (scrollState()?.maxY ?: 0) > 0 }

    private fun await(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_MS)
        }
        return condition()
    }

    /** Runs [block] on the main thread and waits for it, so reads and writes cannot interleave. */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        main.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        check(latch.await(LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "main thread did not run block" }
    }
}
