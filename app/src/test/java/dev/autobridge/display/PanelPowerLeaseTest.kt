package dev.autobridge.display

import org.junit.Assert.*
import org.junit.Test

class PanelPowerLeaseTest {
    @Test fun holdsWakeLockBeforeTurningOffAndRestoresOnlyOnce() {
        val calls = mutableListOf<String>()
        val lease = PanelPowerLease(
            { calls += "awake" }, { calls += "off"; true }, { calls += "on"; true }
        )
        assertTrue(lease.hide())
        assertTrue(lease.hide())
        assertEquals(listOf("awake", "off"), calls)
        assertTrue(lease.restore())
        assertTrue(lease.restore())
        assertEquals(listOf("awake", "off", "on"), calls)
        assertFalse(lease.isOff)
    }

    @Test fun failedOffDoesNotClaimPanelOwnership() {
        var restored = false
        val lease = PanelPowerLease({}, { false }, { restored = true; true })
        assertFalse(lease.hide())
        assertFalse(lease.isOff)
        assertTrue(lease.restore())
        assertFalse(restored)
    }

    @Test fun failedRestoreRetainsOwnershipUntilReconnectAndRetry() {
        var connected = true
        val lease = PanelPowerLease({}, { true }, { connected })
        assertTrue(lease.hide())
        connected = false
        assertFalse(lease.restore())
        assertTrue(lease.isOff)
        assertTrue(lease.restoreFailed)
        connected = true
        assertTrue(lease.restore())
        assertFalse(lease.isOff)
        assertFalse(lease.restoreFailed)
    }

    @Test fun failedOffCanBeRetriedAfterCapabilityBecomesAvailable() {
        var available = false
        var acquisitions = 0
        val lease = PanelPowerLease({ acquisitions++ }, { available }, { true })
        assertFalse(lease.hide())
        available = true
        assertTrue(lease.hide())
        assertEquals(2, acquisitions)
        assertTrue(lease.isOff)
    }
}
