package dev.autobridge.display

import dev.autobridge.settings.AutoDimDelay
import org.junit.Assert.assertEquals
import org.junit.Test

class CarSessionDimPolicyTest {

    @Test
    fun `a chosen delay is used as chosen`() {
        AutoDimDelay.entries.filter { it != AutoDimDelay.OFF }.forEach { chosen ->
            assertEquals(chosen, CarSessionDimPolicy.delay(chosen, panelOffEnabled = true))
            assertEquals(chosen, CarSessionDimPolicy.delay(chosen, panelOffEnabled = false))
        }
    }

    @Test
    fun `off stays off while panel-off is not asked for`() {
        assertEquals(
            AutoDimDelay.OFF,
            CarSessionDimPolicy.delay(AutoDimDelay.OFF, panelOffEnabled = false)
        )
    }

    @Test
    fun `panel-off with no delay gets one, or nothing would ever turn the panel off`() {
        assertEquals(
            CarSessionDimPolicy.FALLBACK,
            CarSessionDimPolicy.delay(AutoDimDelay.OFF, panelOffEnabled = true)
        )
    }

    @Test
    fun `the fallback is a real delay`() {
        assertEquals(true, CarSessionDimPolicy.FALLBACK.seconds > 0)
    }
}
