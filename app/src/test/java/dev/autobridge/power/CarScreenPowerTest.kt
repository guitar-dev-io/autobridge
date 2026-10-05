package dev.autobridge.power

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test

class CarScreenPowerTest {

    private class FakePolicy : CarScreenPowerPolicy {
        var inputs = 0
        var ends = 0
        override fun onSessionStart(context: Context) = Unit
        override fun onCarInput() { inputs++ }
        override fun onSessionEnd() { ends++ }
    }

    @Test
    fun `an installed policy receives the hooks`() {
        val policy = FakePolicy()
        CarScreenPower.install(policy)
        CarScreenPower.carInput()
        CarScreenPower.carInput()
        CarScreenPower.sessionEnded()
        assertEquals(2, policy.inputs)
        assertEquals(1, policy.ends)
    }

    @Test
    fun `installing again replaces the previous policy`() {
        val first = FakePolicy()
        val second = FakePolicy()
        CarScreenPower.install(first)
        CarScreenPower.install(second)
        CarScreenPower.carInput()
        assertEquals(0, first.inputs)
        assertEquals(1, second.inputs)
    }
}
