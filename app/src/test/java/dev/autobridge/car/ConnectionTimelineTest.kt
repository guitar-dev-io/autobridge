package dev.autobridge.car

import dev.autobridge.car.ConnectionTimeline.Kind
import dev.autobridge.logging.StructuredLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConnectionTimelineTest {
    @Before fun setUp() {
        ConnectionTimeline.clear()
        StructuredLog.clear()
    }

    @After fun tearDown() {
        ConnectionTimeline.clear()
        StructuredLog.clear()
    }

    private fun record(kind: Kind, atMs: Long, detail: String = "") = ConnectionTimeline.record(kind, detail, atMs)

    @Test fun anEmptyTimelineHasNoOutage() {
        val summary = ConnectionTimeline.summary()
        assertEquals(0, summary.hostDrops)
        assertNull(summary.lastRecoveryMs)
        assertFalse(summary.outageOpen)
    }

    @Test fun aRepeatedHostSignalIsOneEvent() {
        record(Kind.HOST_CONNECTED, 0)
        record(Kind.HOST_CONNECTED, 10)
        record(Kind.HOST_DISCONNECTED, 20)
        record(Kind.HOST_DISCONNECTED, 30)
        assertEquals(listOf(Kind.HOST_CONNECTED, Kind.HOST_DISCONNECTED), ConnectionTimeline.recent().map { it.kind })
    }

    @Test fun aDisconnectBeforeAnyConnectIsNotADrop() {
        // The host reports NOT_CONNECTED on first observe when the phone is not in a car.
        record(Kind.HOST_DISCONNECTED, 0)
        assertEquals(0, ConnectionTimeline.summary().hostDrops)
        assertTrue(ConnectionTimeline.recent().isEmpty())
    }

    @Test fun hostOutageIsMeasuredFromDropToReturn() {
        record(Kind.HOST_CONNECTED, 0)
        record(Kind.HOST_DISCONNECTED, 1_000)
        assertTrue(ConnectionTimeline.summary().outageOpen)
        record(Kind.HOST_CONNECTED, 4_500)
        val summary = ConnectionTimeline.summary()
        assertEquals(1, summary.hostDrops)
        assertEquals(3_500L, summary.lastRecoveryMs)
        assertFalse(summary.outageOpen)
    }

    @Test fun surfaceResumeCountsAndTimesTheGap() {
        record(Kind.SURFACE_ATTACHED, 0)
        record(Kind.SURFACE_DETACHED, 100)
        record(Kind.SURFACE_RESUMED, 900)
        record(Kind.SURFACE_DETACHED, 2_000)
        record(Kind.SURFACE_RESUMED, 2_200)
        val summary = ConnectionTimeline.summary()
        assertEquals(2, summary.surfaceResumes)
        assertEquals(200L, summary.lastRecoveryMs)
        assertEquals(800L, summary.longestOutageMs)
    }

    @Test fun aProjectionStopClosesAnOpenSurfaceOutageWithoutCallingItRecovered() {
        record(Kind.SURFACE_DETACHED, 0)
        record(Kind.PROJECTION_STOPPED, 2_000, "reason=surface_disconnect")
        val summary = ConnectionTimeline.summary()
        assertEquals(1, summary.projectionStops)
        assertFalse(summary.outageOpen)
        assertNull(summary.lastRecoveryMs)
    }

    @Test fun aFreshAttachAfterADetachIsNotARecovery() {
        record(Kind.SURFACE_DETACHED, 0)
        record(Kind.SURFACE_ATTACHED, 500)
        val summary = ConnectionTimeline.summary()
        assertEquals(0, summary.surfaceResumes)
        assertNull(summary.lastRecoveryMs)
        assertFalse(summary.outageOpen)
    }

    @Test fun everyEventIsOneConnLogLine() {
        record(Kind.SESSION_STARTED, 0)
        record(Kind.PROJECTION_STOPPED, 10, "reason=system")
        assertEquals(
            listOf("SESSION_STARTED", "PROJECTION_STOPPED reason=system"),
            StructuredLog.recent().filter { it.tag == "CONN" }.map { it.message }
        )
    }

    @Test fun aKnownSpeedTurningUnknownIsCountedAsALoss() {
        record(Kind.VEHICLE_STATE, 0, "UNKNOWN")      // start of a session: nothing was known, not a loss
        record(Kind.VEHICLE_STATE, 100, "PARKED")
        record(Kind.VEHICLE_STATE, 3_100, "UNKNOWN")  // stale reading
        record(Kind.VEHICLE_STATE, 4_000, "PARKED")
        record(Kind.VEHICLE_STATE, 5_000, "MOVING")
        record(Kind.VEHICLE_STATE, 9_000, "UNKNOWN")
        assertEquals(2, ConnectionTimeline.summary().speedLosses)
    }

    @Test fun aSpeedLossThenATeardownReadsInOrder() {
        record(Kind.PROJECTION_STARTED, 0)
        record(Kind.VEHICLE_STATE, 10, "PARKED")
        record(Kind.VEHICLE_STATE, 3_010, "UNKNOWN")
        record(Kind.PROJECTION_STOPPED, 3_011, "reason=system")
        assertEquals(
            listOf("PROJECTION_STARTED", "VEHICLE_STATE PARKED", "VEHICLE_STATE UNKNOWN", "PROJECTION_STOPPED reason=system"),
            StructuredLog.recent().filter { it.tag == "CONN" }.map { it.message }
        )
    }

    @Test fun theRingKeepsTheNewestHundred() {
        repeat(130) { record(Kind.SESSION_STARTED, it.toLong()) }
        val events = ConnectionTimeline.recent()
        assertEquals(100, events.size)
        assertEquals(30L, events.first().atMs)
        assertEquals(129L, events.last().atMs)
    }
}
