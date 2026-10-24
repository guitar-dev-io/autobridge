package dev.autobridge.projection.desktop

import dev.autobridge.R
import dev.autobridge.safety.ParkingStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardTypingGateTest {
    @Test
    fun parkedAllows() {
        assertTrue(KeyboardTypingGate.allowed(parked = true, bypass = false, browserAllowed = { true }))
    }

    @Test
    fun movingRefuses() {
        assertFalse(KeyboardTypingGate.allowed(parked = false, bypass = false, browserAllowed = { true }))
    }

    @Test
    fun liveStoreStatesFailClosedUnlessParked() {
        try {
            ParkingStateStore.update(ParkingStateStore.State.PARKED)
            assertTrue(KeyboardTypingGate.allowed(bypass = false, browserAllowed = { true }))
            ParkingStateStore.update(ParkingStateStore.State.MOVING)
            assertFalse(KeyboardTypingGate.allowed(bypass = false, browserAllowed = { true }))
            ParkingStateStore.update(ParkingStateStore.State.UNKNOWN)
            assertFalse(KeyboardTypingGate.allowed(bypass = false, browserAllowed = { true }))
        } finally {
            ParkingStateStore.update(ParkingStateStore.State.UNKNOWN)
        }
    }

    @Test
    fun movingWithBypassAllows() {
        assertTrue(KeyboardTypingGate.allowed(parked = false, bypass = true, browserAllowed = { true }))
    }

    @Test
    fun parkedButBrowserDeniedRefuses() {
        assertFalse(KeyboardTypingGate.allowed(parked = true, bypass = false, browserAllowed = { false }))
        assertFalse(KeyboardTypingGate.allowed(parked = false, bypass = true, browserAllowed = { false }))
    }

    @Test
    fun refusalMessageUnknownWithoutCompanionIsNoVehicleState() {
        assertEquals(
            R.string.desktop_keyboard_no_vehicle_state,
            KeyboardTypingGate.refusalMessage(ParkingStateStore.State.UNKNOWN, null)
        )
    }

    @Test
    fun refusalMessageUnknownWhileCarConnectedIsNoVehicleState() {
        assertEquals(
            R.string.desktop_keyboard_no_vehicle_state,
            KeyboardTypingGate.refusalMessage(ParkingStateStore.State.UNKNOWN, true)
        )
    }

    @Test
    fun refusalMessageUnknownAfterCarSessionEndedIsNoSession() {
        assertEquals(
            R.string.desktop_keyboard_no_session,
            KeyboardTypingGate.refusalMessage(ParkingStateStore.State.UNKNOWN, false)
        )
    }

    @Test
    fun refusalMessageMovingIsParkToBrowse() {
        val park = dev.autobridge.desktop.R.string.desktop_park_to_browse
        assertEquals(park, KeyboardTypingGate.refusalMessage(ParkingStateStore.State.MOVING, null))
        assertEquals(park, KeyboardTypingGate.refusalMessage(ParkingStateStore.State.MOVING, false))
        assertEquals(park, KeyboardTypingGate.refusalMessage(ParkingStateStore.State.MOVING, true))
    }
}
