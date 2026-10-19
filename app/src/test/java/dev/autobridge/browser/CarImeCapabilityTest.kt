package dev.autobridge.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SDK + render-mode gate for the real system keyboard: Android 15+ (SDK 35) AND the HARDWARE
 * render path. This is the <15 / 15+ split the user asked for, pinned so it cannot silently drift.
 * The inconclusive-probe-maps-to-canvas rule lives in [ImeProbeState]; the stateful probe itself is
 * device-only and documented in the design, so the gate is the JVM-testable half.
 */
class CarImeCapabilityTest {

    @Test
    fun `android 14 is blocked even in hardware mode`() {
        assertFalse(CarImeCapability.gate(sdkInt = 34, hardwareMode = true))
    }

    @Test
    fun `android 15 with hardware mode passes the gate (boundary)`() {
        assertTrue(CarImeCapability.gate(sdkInt = 35, hardwareMode = true))
    }

    @Test
    fun `android 16 with hardware mode passes the gate`() {
        assertTrue(CarImeCapability.gate(sdkInt = 36, hardwareMode = true))
    }

    @Test
    fun `legacy canvas mode never passes the gate even on android 15`() {
        assertFalse(CarImeCapability.gate(sdkInt = 35, hardwareMode = false))
    }

    @Test
    fun `legacy canvas mode on android 14 never passes the gate`() {
        assertFalse(CarImeCapability.gate(sdkInt = 34, hardwareMode = false))
    }
}
