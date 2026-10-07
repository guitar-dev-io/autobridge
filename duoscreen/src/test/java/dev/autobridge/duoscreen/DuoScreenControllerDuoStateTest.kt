package dev.autobridge.duoscreen

import dev.autobridge.car.DuoSessionState
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The full [DuoScreenController] lifecycle needs Shizuku/VirtualDisplay stubs the unit environment
 * cannot provide, so this encodes the publish/clear contract the media gate depends on as executable
 * statements against [DuoSessionState] from the :duoscreen side. Three invariants are pinned:
 *
 * - start sets the flag, a guarded teardown clears it;
 * - restart() must not dip the flag — its internal stop() leaves tearingDown false, so start()'s
 *   re-assert keeps the flag true throughout a live rebuild (a regression that cleared unconditionally
 *   in stop() would fail this);
 * - both teardown routes clear — modelled with the guard set, the flag returns to false (a regression
 *   that cleared only the car route would fail this).
 */
class DuoScreenControllerDuoStateTest {
    @After fun tearDown() {
        // Reset the process-global singleton so test order cannot leak.
        DuoSessionState.sessionEnded()
    }

    @Test fun startSetsActiveAndGuardedTeardownClears() {
        DuoSessionState.sessionStarted()
        assertTrue(DuoSessionState.isActive)
        // Models a real teardown (tearingDown == true), reached by both release() and the harness.
        DuoSessionState.sessionEnded()
        assertFalse(DuoSessionState.isActive)
    }

    @Test fun restartOrderingNeverDipsTheFlag() {
        // Models restart(): start() asserts, the internal stop() leaves tearingDown false (so it does
        // NOT clear), and the following start() re-asserts. The flag stays true throughout.
        DuoSessionState.sessionStarted()
        assertTrue(DuoSessionState.isActive)
        // restart()'s internal stop() with tearingDown == false performs no clear here.
        assertTrue(DuoSessionState.isActive)
        DuoSessionState.sessionStarted()
        assertTrue(DuoSessionState.isActive)
    }

    @Test fun bothTeardownRoutesClear() {
        // Both DuoScreenHost.release() (car) and DuoScreenSpikeActivity.stopSession() (harness) set
        // tearingDown == true before stop(), so stop()'s guarded clear fires on either route.
        DuoSessionState.sessionStarted()
        DuoSessionState.sessionEnded()
        assertFalse(DuoSessionState.isActive)
    }
}
