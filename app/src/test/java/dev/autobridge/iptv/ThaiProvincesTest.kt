package dev.autobridge.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The curated province list and its single validity predicate. */
class ThaiProvincesTest {
    @Test fun `list is non-empty`() {
        assertTrue(ThaiProvinces.all.isNotEmpty())
    }

    @Test fun `state keys are non-blank`() {
        assertTrue(ThaiProvinces.all.all { it.state.isNotBlank() })
    }

    @Test fun `state keys are unique`() {
        val states = ThaiProvinces.all.map { it.state }
        assertEquals(states.size, states.toSet().size)
    }

    @Test fun `every entry carries a label resource id`() {
        // Structural check: a real @StringRes is a non-zero generated id.
        assertTrue(ThaiProvinces.all.all { it.labelRes != 0 })
    }

    @Test fun `label resource ids are unique`() {
        val ids = ThaiProvinces.all.map { it.labelRes }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun `isKnown is true for a known state`() {
        val known = ThaiProvinces.all.first().state
        assertTrue(ThaiProvinces.isKnown(known))
    }

    @Test fun `isKnown is false for a blank value`() {
        assertFalse(ThaiProvinces.isKnown(""))
        assertFalse(ThaiProvinces.isKnown("   "))
    }

    @Test fun `isKnown is false for an off-list value`() {
        assertFalse(ThaiProvinces.isKnown("Atlantis"))
    }
}
