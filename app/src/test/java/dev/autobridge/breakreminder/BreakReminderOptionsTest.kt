package dev.autobridge.breakreminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BreakReminderOptionsTest {
    @Test fun eachChoiceIsThatManyHours() {
        assertEquals(3_600_000L, BreakReminderOptions.intervalMs(1))
        assertEquals(7_200_000L, BreakReminderOptions.intervalMs(2))
        assertEquals(14_400_000L, BreakReminderOptions.intervalMs(4))
    }

    @Test fun offAndStrayValuesGiveNoTimer() {
        assertNull(BreakReminderOptions.intervalMs(0))
        assertNull(BreakReminderOptions.intervalMs(-3))
        assertNull(BreakReminderOptions.intervalMs(24))
    }

    @Test fun sanitizeKeepsChoicesAndOtherwiseTurnsOff() {
        assertEquals(2, BreakReminderOptions.sanitize(2))
        assertEquals(0, BreakReminderOptions.sanitize(7))
        assertEquals(0, BreakReminderOptions.sanitize(-1))
    }
}
