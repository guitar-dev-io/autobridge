package dev.autobridge.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdapterNameTest {
    @Test fun theLowEnergyNameLosesItsPlatformSuffix() {
        assertEquals("vLinker FD (BLE)", ObdAdapterMatcher.display("vLinker FD-IOS"))
        assertEquals("vLinker MC (BLE)", ObdAdapterMatcher.display("vLinker MC-ios"))
    }

    @Test fun theClassicNameSaysSo() {
        assertEquals("vLinker FD (Classic)", ObdAdapterMatcher.display("vLinker FD-Android"))
    }

    @Test fun otherNamesAreLeftAlone() {
        assertEquals("OBDII", ObdAdapterMatcher.display("OBDII"))
        assertEquals("Veepeak BLE", ObdAdapterMatcher.display("Veepeak BLE"))
        assertNull(ObdAdapterMatcher.display(null))
    }
}
