package dev.autobridge.emergency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmergencyNumberTest {
    @Test fun shortEmergencyNumbersAreDialable() {
        assertEquals("191", EmergencyNumber.dialable("191"))
        assertEquals("1669", EmergencyNumber.dialable(" 1669 "))
    }

    @Test fun separatorsAreDroppedAndAPlusIsKept() {
        assertEquals("021234567", EmergencyNumber.dialable("02-123-4567"))
        assertEquals("+66812345678", EmergencyNumber.dialable("+66 81 234 5678"))
        assertEquals("0812345678", EmergencyNumber.dialable("(081) 234.5678"))
    }

    @Test fun aPolicyNumberIsTextNotAPhone() {
        assertNull(EmergencyNumber.dialable("AB-1234567"))
        assertNull(EmergencyNumber.dialable("policy 12345"))
        assertNull(EmergencyNumber.dialable(""))
        assertNull(EmergencyNumber.dialable("12"))
    }

    @Test fun anEntryKnowsItsOwnNumber() {
        assertEquals("1193", EmergencyEntry(1, "Highway police", "1193").dialable)
        assertNull(EmergencyEntry(2, "Policy", "ABC123").dialable)
    }
}
