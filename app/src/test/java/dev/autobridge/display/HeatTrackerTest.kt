package dev.autobridge.display

import org.junit.Assert.assertEquals
import org.junit.Test

class HeatTrackerTest {
    @Test fun summaryReportsStartEndPeakAndBatteryDrop() {
        val tracker = HeatTracker()
        tracker.record(33.0, 80)
        tracker.record(41.5, 76)
        tracker.record(39.0, 74)
        assertEquals(
            "session 12 min: battery 33.0 C to 39.0 C, peak 41.5 C, level 80 to 74 percent",
            tracker.summary(12 * 60_000L)
        )
    }

    @Test fun summaryWithoutAReadingSaysSo() {
        assertEquals("session 3 min: no battery reading", HeatTracker().summary(3 * 60_000L))
    }

    @Test fun lineShowsTheLatestReadingChargingAndThermalStatus() {
        val tracker = HeatTracker()
        tracker.record(36.2, 90)
        tracker.record(38.7, 88)
        assertEquals(
            "min 5: battery 38.7 C, 88 percent, charging, thermal moderate",
            tracker.line(5, thermalStatus = 2, charging = true)
        )
    }

    @Test fun anUnknownThermalStatusIsNamedUnknown() {
        assertEquals("unknown", HeatTracker.thermalName(42))
    }
}
