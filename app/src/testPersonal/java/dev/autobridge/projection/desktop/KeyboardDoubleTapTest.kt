package dev.autobridge.projection.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardDoubleTapTest {
    private var now = 1_000L
    private class Task(val at: Long, val block: () -> Unit) { var cancelled = false }
    private val tasks = mutableListOf<Task>()
    private val taps = mutableListOf<Pair<Float, Float>>()

    /** Tray is everything below y = 400. */
    private val tap = KeyboardDoubleTap(
        clock = { now },
        schedule = { delay, block -> Task(now + delay, block).also { tasks += it }.let { t -> { t.cancelled = true } } },
        density = { 2f },
        onTray = { _, y -> y >= 400f },
        replay = { x, y -> taps += x to y },
    )

    private fun advance(ms: Long) {
        now += ms
        tasks.filter { !it.cancelled && it.at <= now }.forEach { tasks -= it; it.block() }
    }

    @Test
    fun isolatedBigScaleOnTrayReplaysTwoTaps() {
        tap.onScale(100f, 500f, 2.0f)
        assertEquals(0, taps.size)
        advance(KeyboardDoubleTap.DECIDE_MS)
        assertEquals(listOf(100f to 500f, 100f to 500f), taps)
    }

    @Test
    fun shrinkingFactorCountsToo() {
        tap.onScale(100f, 500f, 0.5f)
        advance(KeyboardDoubleTap.DECIDE_MS)
        assertEquals(2, taps.size)
    }

    @Test
    fun afterADeliveredClickReplaysOnce() {
        tap.onClick(110f, 505f)
        advance(150)
        tap.onScale(100f, 500f, 2.0f)
        advance(KeyboardDoubleTap.DECIDE_MS)
        assertEquals(1, taps.size)
    }

    @Test
    fun clickTooFarOrTooOldStillReplaysTwice() {
        tap.onClick(100f + 49f * 2f, 500f) // 49 dp away at density 2
        advance(100)
        tap.onScale(100f, 500f, 2.0f)
        advance(KeyboardDoubleTap.DECIDE_MS)
        assertEquals(2, taps.size)

        taps.clear()
        advance(1_000)
        tap.onClick(100f, 500f)
        advance(KeyboardDoubleTap.CLICK_WINDOW_MS + 1)
        tap.onScale(100f, 500f, 2.0f)
        advance(KeyboardDoubleTap.DECIDE_MS)
        assertEquals(2, taps.size)
    }

    @Test
    fun smallFactorIsIgnored() {
        tap.onScale(100f, 500f, 1.2f)
        advance(500)
        assertEquals(0, taps.size)
    }

    @Test
    fun secondScaleWithinDecisionWindowCancelsTheCandidate() {
        tap.onScale(100f, 500f, 2.0f)
        advance(50)
        tap.onScale(100f, 500f, 2.2f)
        advance(500)
        assertEquals(0, taps.size)
    }

    @Test
    fun pinchStreamIsIgnoredUntilSilence() {
        tap.onScale(100f, 500f, 1.05f)
        repeat(10) {
            advance(40)
            tap.onScale(100f, 500f, 2.0f)
        }
        advance(KeyboardDoubleTap.DECIDE_MS)
        assertEquals(0, taps.size)
        // After 300 ms of silence a lone double tap counts again.
        advance(KeyboardDoubleTap.ISOLATION_MS)
        tap.onScale(100f, 500f, 2.0f)
        advance(KeyboardDoubleTap.DECIDE_MS)
        assertEquals(2, taps.size)
    }

    @Test
    fun focusOutsideTrayIsIgnored() {
        tap.onScale(100f, 200f, 2.0f)
        advance(500)
        assertEquals(0, taps.size)
    }

    @Test
    fun cancelDropsPendingDecision() {
        tap.onScale(100f, 500f, 2.0f)
        tap.cancel()
        advance(500)
        assertEquals(0, taps.size)
    }
}
