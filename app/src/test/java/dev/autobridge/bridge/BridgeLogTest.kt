package dev.autobridge.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The log's redaction.
 *
 * The in-app log screen is readable from the car, and a shared link's query can carry a session
 * token, so parameter values are dropped while the parameter names — the part that makes a
 * routing decision readable — are kept.
 */
class BridgeLogTest {

    @Test
    fun `a url with no query is untouched`() {
        assertEquals("https://example.com/page", BridgeLog.redact("https://example.com/page"))
    }

    @Test
    fun `parameter values are replaced but their names survive`() {
        assertEquals(
            "https://example.com/p?token=…&session=…",
            BridgeLog.redact("https://example.com/p?token=secret&session=abc123")
        )
    }

    @Test
    fun `the youtube video id and timestamp are kept`() {
        // Without these two, consecutive sends are indistinguishable in a log.
        assertEquals(
            "https://m.youtube.com/watch?v=abc123&t=90",
            BridgeLog.redact("https://m.youtube.com/watch?v=abc123&t=90")
        )
    }

    @Test
    fun `a fragment is dropped`() {
        assertEquals("https://example.com/p", BridgeLog.redact("https://example.com/p#section"))
    }
}
