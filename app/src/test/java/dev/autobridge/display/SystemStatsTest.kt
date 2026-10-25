package dev.autobridge.display

import android.os.PowerManager
import dev.autobridge.display.SystemStats.Heat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The arithmetic behind the car's CPU / RAM / temperature line; the readings themselves need a device. */
class SystemStatsTest {
    @Test fun cpuIsTheShareOfAllCores() {
        // 800 ms of CPU in one second on 8 cores = 10 % of the whole CPU.
        assertEquals(10, SystemStats.cpuPercent(cpuMsDelta = 800, wallMsDelta = 1_000, cores = 8))
    }

    @Test fun cpuCannotExceedOneHundred() {
        assertEquals(100, SystemStats.cpuPercent(cpuMsDelta = 40_000, wallMsDelta = 1_000, cores = 8))
    }

    @Test fun cpuHasNoValueWithoutElapsedTime() {
        assertNull(SystemStats.cpuPercent(cpuMsDelta = 10, wallMsDelta = 0, cores = 8))
        assertNull(SystemStats.cpuPercent(cpuMsDelta = -5, wallMsDelta = 1_000, cores = 8))
        assertNull(SystemStats.cpuPercent(cpuMsDelta = 10, wallMsDelta = 1_000, cores = 0))
    }

    @Test fun ramIsTheUsedShare() {
        assertEquals(75, SystemStats.ramPercent(totalBytes = 8_000, availBytes = 2_000))
        assertEquals(0, SystemStats.ramPercent(totalBytes = 0, availBytes = 0))
        // A reading that reports more free than total is not a negative.
        assertEquals(0, SystemStats.ramPercent(totalBytes = 1_000, availBytes = 5_000))
    }

    @Test fun theSystemsThermalStatusWinsOverTheBatteryTemperature() {
        assertEquals(Heat.HOT, SystemStats.heat(tempC = 30f, thermalStatus = PowerManager.THERMAL_STATUS_SEVERE))
        assertEquals(Heat.WARM, SystemStats.heat(tempC = 30f, thermalStatus = PowerManager.THERMAL_STATUS_MODERATE))
    }

    @Test fun withoutThermalPressureTheBatteryTemperatureDecides() {
        assertEquals(Heat.OK, SystemStats.heat(tempC = 39.9f, thermalStatus = 0))
        assertEquals(Heat.WARM, SystemStats.heat(tempC = 40f, thermalStatus = 0))
        assertEquals(Heat.HOT, SystemStats.heat(tempC = 45f, thermalStatus = 0))
        assertEquals(Heat.OK, SystemStats.heat(tempC = null, thermalStatus = 0))
    }
}
