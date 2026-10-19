package dev.autobridge.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The render-mode gate for the real system keyboard: the HARDWARE render path, on any Android
 * version (the runtime probe decides whether an IME actually appears). The inconclusive-probe-maps-
 * to-canvas rule lives in [ImeProbeState]; the stateful probe itself is device-only and documented
 * in the design, so the gate is the JVM-testable half.
 */
class CarImeCapabilityTest {

    @Test
    fun `hardware mode passes the gate`() {
        assertTrue(CarImeCapability.gate(hardwareMode = true))
    }

    @Test
    fun `legacy canvas mode never passes the gate`() {
        assertFalse(CarImeCapability.gate(hardwareMode = false))
    }
}
