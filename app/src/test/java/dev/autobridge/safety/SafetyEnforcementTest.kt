package dev.autobridge.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate is observed, not enforced. These assertions are what tells a future reader that the two
 * halves have not drifted back together: a status row that quietly starts reading the gate's flag
 * instead of the store would show PARKED on a moving car again, which is the exact fault the
 * previous bypass introduced.
 */
class SafetyEnforcementTest {

    @Test fun shippedDefaultReportsRatherThanBlocks() {
        assertFalse(SafetyEnforcement.isBlocking)
    }

    @Test fun gateLetsEveryStateThroughWhileOnlyReporting() {
        // The caller's own `&& parked` must stop mattering, whatever the real reading is.
        assertTrue(SafetyEnforcement.gateParked(actual = true))
        assertTrue(SafetyEnforcement.gateParked(actual = false))
    }

    @Test fun statusLabelShowsTheRealStateAndDoesNotClaimABlock() {
        assertEquals("Parked", SafetyEnforcement.statusLabel(ParkingStateStore.State.PARKED))
        assertEquals("Moving", SafetyEnforcement.statusLabel(ParkingStateStore.State.MOVING))
        assertEquals("Unknown", SafetyEnforcement.statusLabel(ParkingStateStore.State.UNKNOWN))
    }

    @Test fun storeStillPublishesMotionToItsListeners() {
        val seen = mutableListOf<ParkingStateStore.State>()
        val listener: (ParkingStateStore.State) -> Unit = { seen += it }
        ParkingStateStore.addListener(listener)
        try {
            ParkingStateStore.update(ParkingStateStore.State.PARKED)
            ParkingStateStore.update(ParkingStateStore.State.MOVING)
            ParkingStateStore.update(ParkingStateStore.State.UNKNOWN)
        } finally {
            ParkingStateStore.removeListener(listener)
        }

        assertEquals(
            listOf(
                ParkingStateStore.State.PARKED,
                ParkingStateStore.State.MOVING,
                ParkingStateStore.State.UNKNOWN
            ),
            seen
        )
    }
}
