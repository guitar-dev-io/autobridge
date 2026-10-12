package dev.autobridge.mirror

import dev.autobridge.safety.ParkingStateStore.State
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorParkedTeardownTest {

    @Test fun withoutTheBypassEveryStateButParkedTearsDown() {
        assertFalse(MirrorParkedTeardown.shouldTearDown(State.PARKED, bypassOverridesParked = false))
        assertTrue(MirrorParkedTeardown.shouldTearDown(State.MOVING, bypassOverridesParked = false))
        // Stale speed and an ended car session both read UNKNOWN; still fail-closed by default.
        assertTrue(MirrorParkedTeardown.shouldTearDown(State.UNKNOWN, bypassOverridesParked = false))
    }

    @Test fun withTheBypassTheSessionIsKeptMovingOrStopped() {
        State.entries.forEach { state ->
            assertFalse(state.name, MirrorParkedTeardown.shouldTearDown(state, bypassOverridesParked = true))
        }
    }
}
