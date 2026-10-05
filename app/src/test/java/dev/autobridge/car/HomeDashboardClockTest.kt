package dev.autobridge.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeDashboardClockTest {

    @Test
    fun `under an hour is minutes and seconds`() {
        assertEquals("12:34", HomeDashboardClock.clock(754_000L))
        assertEquals("0:07", HomeDashboardClock.clock(7_000L))
    }

    @Test
    fun `an hour or more grows a leading hours field`() {
        assertEquals("1:02:34", HomeDashboardClock.clock(3_754_000L))
        assertEquals("10:00:00", HomeDashboardClock.clock(36_000_000L))
    }

    @Test
    fun `seconds round to the nearest, so a position never reads behind by one`() {
        assertEquals("0:01", HomeDashboardClock.clock(600L))
        assertEquals("0:00", HomeDashboardClock.clock(400L))
    }

    @Test
    fun `a duration nobody reported has no text rather than a zero`() {
        assertNull(HomeDashboardClock.duration(0L))
        assertNull(HomeDashboardClock.duration(-1L))
        assertEquals("31:20", HomeDashboardClock.duration(1_880_000L))
    }

    @Test
    fun `elapsed drops the total when there is none to show`() {
        assertEquals("12:34 / 31:20", HomeDashboardClock.elapsed(754_000L, 1_880_000L))
        assertEquals("12:34", HomeDashboardClock.elapsed(754_000L, 0L))
    }

    @Test
    fun `a negative position reads as the start, not as a negative clock`() {
        assertEquals("0:00", HomeDashboardClock.elapsed(-5_000L, 0L))
    }
}
