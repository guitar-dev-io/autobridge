package dev.autobridge.display

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mirror's lifecycle trail has to outlive the process.
 *
 * Its event ring is memory only, so the sequence `docs/DHU_SCENARIOS.md` asks to be confirmed used
 * to be readable on one screen of the car's own and nowhere else — and a mirror fault that kills the
 * app is exactly when that history is wanted. [MirrorDiagnostics.record] now also goes through
 * [StructuredLog], which reaches logcat and the on-disk sink.
 */
class MirrorDiagnosticsLogTest {
    @After fun tearDown() {
        StructuredLog.sink = null
        StructuredLog.clear()
        MirrorDiagnostics.reset()
    }

    @Test fun everyLifecycleEventIsBothRememberedAndLogged() {
        StructuredLog.clear()
        MirrorDiagnostics.reset()

        MirrorDiagnostics.record("car_surface_attached", nowMs = 10)
        MirrorDiagnostics.record("virtual_display_created", nowMs = 20)

        // The in-app screen still reads the ring, unchanged.
        assertEquals(
            listOf("car_surface_attached", "virtual_display_created"),
            MirrorDiagnostics.recent().map { it.label }
        )
        // And the same story is now in the structured log, in the same order.
        val logged = StructuredLog.recent().filter { it.tag == "MIRROR" }
        assertEquals(
            listOf("car_surface_attached", "virtual_display_created"),
            logged.map { it.message }
        )
        assertTrue("mirror events should log at INFO", logged.all { it.level == StructuredLog.Level.INFO })
    }

    @Test fun theFrameCountersStayOutOfTheLog() {
        StructuredLog.clear()
        MirrorDiagnostics.reset()

        // At display rate these would bury every event that matters, so they are counters only.
        repeat(5) {
            MirrorDiagnostics.recordFrameCaptured()
            MirrorDiagnostics.recordFrameRendered(latencyMs = 8, nowMs = 100)
        }
        MirrorDiagnostics.recordFrameDropped(3)

        assertEquals(emptyList<String>(), StructuredLog.recent().filter { it.tag == "MIRROR" }.map { it.message })
        assertEquals(5, MirrorDiagnostics.frameStats().rendered)
        assertEquals(3, MirrorDiagnostics.frameStats().dropped)
    }

    @Test fun theMirroringClockEventsAreLoggedOncePerTransition() {
        StructuredLog.clear()
        MirrorDiagnostics.reset()

        MirrorDiagnostics.onMirroringActiveChanged(isActive = true, nowMs = 100)
        // Repeated "still active" is not a transition and must not log again.
        MirrorDiagnostics.onMirroringActiveChanged(isActive = true, nowMs = 200)
        MirrorDiagnostics.onMirroringActiveChanged(isActive = false, nowMs = 300)
        MirrorDiagnostics.onMirroringActiveChanged(isActive = false, nowMs = 400)

        assertEquals(
            listOf("mirroring_active", "mirroring_inactive"),
            StructuredLog.recent().filter { it.tag == "MIRROR" }.map { it.message }
        )
    }
}
