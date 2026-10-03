package dev.autobridge.remotestream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The retry schedule, and the one case that must never retry. */
class ReconnectPolicyTest {

    @Test
    fun `the first connection is immediate`() {
        assertEquals(0L, ReconnectPolicy.delayMs(0))
        assertEquals(0L, ReconnectPolicy.delayMs(-1))
    }

    @Test
    fun `the backoff doubles up to the ceiling`() {
        assertEquals(1_000L, ReconnectPolicy.delayMs(1))
        assertEquals(2_000L, ReconnectPolicy.delayMs(2))
        assertEquals(4_000L, ReconnectPolicy.delayMs(3))
        assertEquals(8_000L, ReconnectPolicy.delayMs(4))
        assertEquals(16_000L, ReconnectPolicy.delayMs(5))
        assertEquals(ReconnectPolicy.MAX_DELAY_MS, ReconnectPolicy.delayMs(6))
    }

    @Test
    fun `a long outage never exceeds the ceiling or overflows`() {
        // The shift is clamped; without that, a drive-long outage would eventually shift past 63
        // and produce a negative delay, which postDelayed treats as "run now" — a reconnect
        // storm at exactly the worst moment.
        listOf(20, 40, 100, Int.MAX_VALUE).forEach { attempt ->
            assertEquals(ReconnectPolicy.MAX_DELAY_MS, ReconnectPolicy.delayMs(attempt))
        }
    }

    @Test
    fun `a user stop is never retried`() {
        assertFalse(ReconnectPolicy.shouldRetry(RemoteStreamStatus.ERROR, userStopped = true))
        assertFalse(ReconnectPolicy.shouldRetry(RemoteStreamStatus.DISCONNECTED, userStopped = true))
    }

    @Test
    fun `a drop is retried and a live link is not`() {
        assertTrue(ReconnectPolicy.shouldRetry(RemoteStreamStatus.ERROR, userStopped = false))
        assertTrue(ReconnectPolicy.shouldRetry(RemoteStreamStatus.DISCONNECTED, userStopped = false))
        assertFalse(ReconnectPolicy.shouldRetry(RemoteStreamStatus.CONNECTED, userStopped = false))
    }
}
