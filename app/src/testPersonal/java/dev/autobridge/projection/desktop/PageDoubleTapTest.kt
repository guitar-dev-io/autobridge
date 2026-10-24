package dev.autobridge.projection.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class PageDoubleTapTest {
    private var now = 1_000L
    private class Task(val at: Long, val block: () -> Unit) { var cancelled = false }
    private val tasks = mutableListOf<Task>()
    private val clicks = mutableListOf<Pair<Float, Float>>()
    private val zooms = mutableListOf<Triple<Float, Float, Float>>()

    private val tap = PageDoubleTap(
        clock = { now },
        schedule = { delay, block -> Task(now + delay, block).also { tasks += it }.let { t -> { t.cancelled = true } } },
        doubleClick = { x, y -> clicks += x to y },
        zoom = { x, y, f -> zooms += Triple(x, y, f) },
    )

    private fun advance(ms: Long) {
        now += ms
        tasks.filter { !it.cancelled && it.at <= now }.forEach { tasks -= it; it.block() }
    }

    @Test
    fun isolatedBigScaleDoubleClicks() {
        tap.onScale(300f, 400f, 2.0f)
        assertEquals(0, clicks.size)
        advance(PageDoubleTap.DECIDE_MS)
        assertEquals(listOf(300f to 400f), clicks)
        assertEquals(0, zooms.size)
    }

    @Test
    fun shrinkingBigScaleDoubleClicksToo() {
        tap.onScale(300f, 400f, 0.5f)
        advance(PageDoubleTap.DECIDE_MS)
        assertEquals(1, clicks.size)
    }

    @Test
    fun twoQuickScalesArePinchNotDoubleClick() {
        tap.onScale(300f, 400f, 2.0f)
        advance(50)
        tap.onScale(300f, 400f, 2.2f)
        advance(PageDoubleTap.DECIDE_MS)
        assertEquals(0, clicks.size)
        // The second event zoomed rather than double-clicking.
        assertEquals(1, zooms.size)
    }

    @Test
    fun smallFactorZoomsAndNeverDoubleClicks() {
        tap.onScale(300f, 400f, 1.1f)
        advance(PageDoubleTap.DECIDE_MS)
        assertEquals(0, clicks.size)
        assertEquals(1, zooms.size)
    }

    @Test
    fun pinchRunZoomsAndNeverDoubleClicks() {
        tap.onScale(300f, 400f, 1.05f)
        repeat(10) {
            advance(40)
            tap.onScale(300f, 400f, 1.05f)
        }
        advance(PageDoubleTap.DECIDE_MS)
        assertEquals(0, clicks.size)
    }

    @Test
    fun cancelDropsPendingDoubleClick() {
        tap.onScale(300f, 400f, 2.0f)
        tap.cancel()
        advance(PageDoubleTap.DECIDE_MS)
        assertEquals(0, clicks.size)
    }
}
