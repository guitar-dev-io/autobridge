package dev.autobridge.ui

import dev.autobridge.mirror.ProjectionService.SessionState
import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorStatusTest {
    @Test fun idleServiceWithNothingMirroredIsIdle() {
        assertEquals(MirrorStatus.IDLE, MirrorStatus.of(SessionState.IDLE, mirroring = false))
    }

    @Test fun aStartingServiceIsConnecting() {
        assertEquals(MirrorStatus.CONNECTING, MirrorStatus.of(SessionState.STARTING, mirroring = false))
    }

    @Test fun mirroringWinsOverAnyServiceState() {
        assertEquals(MirrorStatus.LIVE, MirrorStatus.of(SessionState.READY, mirroring = true))
        assertEquals(MirrorStatus.LIVE, MirrorStatus.of(SessionState.STARTING, mirroring = true))
    }

    @Test fun anErrorWithNothingMirroredShowsTheError() {
        assertEquals(MirrorStatus.ERROR, MirrorStatus.of(SessionState.ERROR, mirroring = false))
    }
}
