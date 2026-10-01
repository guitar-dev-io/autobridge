package dev.autobridge.ui

import dev.autobridge.core.model.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStatusTextTest {

    @Test fun connectedAndParkedReadsLikeTheMock() {
        val status = ConnectionStatusText.of(true, VehicleState.PARKED)
        assertTrue(status.connected)
        assertEquals("Connected · Parked", status.summary)
    }

    @Test fun disconnectedIgnoresVehicleState() {
        VehicleState.entries.forEach {
            val status = ConnectionStatusText.of(false, it)
            assertFalse(status.connected)
            assertEquals("Not connected", status.summary)
        }
    }

    @Test fun neverShowsInternalTerminology() {
        val internal = listOf("REAL_CAR", "DHU", "EMULATOR", "TEST_BENCH", "LAB", "PERSONAL", "SAFE", "MOVING", "UNKNOWN")
        listOf(true, false).forEach { connected ->
            VehicleState.entries.forEach { vehicle ->
                val text = ConnectionStatusText.of(connected, vehicle).summary
                internal.forEach { word -> assertFalse("$text leaks $word", text.contains(word)) }
            }
        }
    }

    @Test fun drivingIsReportedAsDriving() {
        assertEquals("Connected · Driving", ConnectionStatusText.of(true, VehicleState.MOVING).summary)
    }
}
