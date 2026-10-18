package dev.autobridge.desktop.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserRelaunchPolicyTest {
    @Test
    fun `closing the last tab relaunches the browser`() {
        assertTrue(BrowserRelaunchPolicy().shouldRelaunch(0, 1_000))
    }

    @Test
    fun `a crash is not relaunched`() {
        val policy = BrowserRelaunchPolicy()
        assertFalse(policy.shouldRelaunch(1, 1_000))
        assertFalse(policy.shouldRelaunch(139, 2_000))
    }

    @Test
    fun `a browser that keeps quitting is given up on`() {
        val policy = BrowserRelaunchPolicy(maxRelaunches = 3, windowMs = 30_000)
        assertTrue(policy.shouldRelaunch(0, 0))
        assertTrue(policy.shouldRelaunch(0, 1_000))
        assertTrue(policy.shouldRelaunch(0, 2_000))
        assertFalse(policy.shouldRelaunch(0, 3_000))
    }

    @Test
    fun `old relaunches stop counting`() {
        val policy = BrowserRelaunchPolicy(maxRelaunches = 3, windowMs = 30_000)
        repeat(3) { assertTrue(policy.shouldRelaunch(0, it * 1_000L)) }
        assertTrue(policy.shouldRelaunch(0, 31_000))
    }
}
