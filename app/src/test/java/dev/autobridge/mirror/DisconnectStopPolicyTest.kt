package dev.autobridge.mirror

import dev.autobridge.mirror.DisconnectStopPolicy.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisconnectStopPolicyTest {
    private fun decide(
        stopOnDisconnect: Boolean = true,
        sessionIdle: Boolean = false,
        hostSignalKnown: Boolean = true,
        hostConnected: Boolean = true,
        surfaceReady: Boolean = false
    ) = DisconnectStopPolicy.decide(stopOnDisconnect, sessionIdle, hostSignalKnown, hostConnected, surfaceReady)

    /** The bug this replaces: the surface goes away on Maps, but the car is still there. */
    @Test fun switchingAppsOnTheCarScreenKeepsTheSession() {
        assertNull(decide(hostConnected = true, surfaceReady = false))
    }

    @Test fun aRealDisconnectStops() {
        assertEquals(Reason.HOST_DISCONNECTED, decide(hostConnected = false))
    }

    @Test fun theHostSignalWinsEvenWhenASurfaceIsStillTracked() {
        assertEquals(Reason.HOST_DISCONNECTED, decide(hostConnected = false, surfaceReady = true))
    }

    @Test fun withoutAHostSignalTheSurfaceDecidesAsBefore() {
        assertEquals(Reason.SURFACE_LOST, decide(hostSignalKnown = false, hostConnected = false, surfaceReady = false))
        assertNull(decide(hostSignalKnown = false, hostConnected = false, surfaceReady = true))
    }

    @Test fun nothingStopsWhenTheSettingIsOffOrNothingIsRunning() {
        assertNull(decide(stopOnDisconnect = false, hostConnected = false))
        assertNull(decide(sessionIdle = true, hostConnected = false))
    }

    @Test fun reasonsUseTheLabelsTheTimelineAlreadyRecords() {
        assertEquals("host_disconnect", Reason.HOST_DISCONNECTED.label)
        assertEquals("surface_disconnect", Reason.SURFACE_LOST.label)
    }
}
