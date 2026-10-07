package dev.autobridge.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MediaAutoStart.startSession] builds a real MediaController, so the gate decision is extracted
 * into the pure predicate [MediaAutoStart.shouldStartSession] and asserted here without
 * constructing a controller (FR3 / AC5: gated during Duo, unchanged otherwise).
 */
class MediaAutoStartDuoGateTest {
    @Test fun duoActiveDoesNotStartTheSession() {
        assertFalse(MediaAutoStart.shouldStartSession(duoActive = true))
    }

    @Test fun duoInactiveStartsTheSession() {
        assertTrue(MediaAutoStart.shouldStartSession(duoActive = false))
    }
}
