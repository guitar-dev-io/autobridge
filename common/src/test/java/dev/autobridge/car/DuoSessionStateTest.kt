package dev.autobridge.car

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The holder is a single process-global boolean read by :app's media layer. Three properties
 * matter and none needs Android: the default is "Duo not active" (so the safe flavor, which never
 * writes it, behaves exactly as today, NFR4/AC8), start/end flip it (AC9), and both mutators are
 * idempotent (the resume + fresh-start paths both call sessionStarted, and both teardown routes land
 * on the one guarded clear).
 */
class DuoSessionStateTest {
    @After fun tearDown() {
        // Reset the process-global singleton so test order cannot leak.
        DuoSessionState.sessionEnded()
    }

    @Test fun defaultIsInactive() {
        assertFalse(DuoSessionState.isActive)
    }

    @Test fun sessionStartedActivatesAndSessionEndedClears() {
        DuoSessionState.sessionStarted()
        assertTrue(DuoSessionState.isActive)
        DuoSessionState.sessionEnded()
        assertFalse(DuoSessionState.isActive)
    }

    @Test fun repeatedMutatorsAreIdempotent() {
        DuoSessionState.sessionStarted()
        DuoSessionState.sessionStarted()
        assertTrue(DuoSessionState.isActive)
        DuoSessionState.sessionEnded()
        DuoSessionState.sessionEnded()
        assertFalse(DuoSessionState.isActive)
    }
}
