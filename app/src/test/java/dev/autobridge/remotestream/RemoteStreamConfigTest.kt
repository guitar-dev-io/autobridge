package dev.autobridge.remotestream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What counts as a usable host address, and what turns the feature on. */
class RemoteStreamConfigTest {

    @Test
    fun `a bare host and port is assumed plaintext websocket`() {
        // What the user reads off the host's own console; rejecting it would make the setting
        // refuse its most obvious input.
        assertEquals("ws://192.168.1.20:8080", RemoteStreamConfig.normalizeEndpoint("192.168.1.20:8080"))
    }

    @Test
    fun `explicit schemes are kept`() {
        assertEquals("ws://host:9/stream", RemoteStreamConfig.normalizeEndpoint("ws://host:9/stream"))
        assertEquals("wss://host/stream", RemoteStreamConfig.normalizeEndpoint("wss://host/stream"))
    }

    @Test
    fun `non-websocket schemes are refused`() {
        assertNull(RemoteStreamConfig.normalizeEndpoint("http://host:8080"))
        assertNull(RemoteStreamConfig.normalizeEndpoint("rtsp://host/stream"))
        assertNull(RemoteStreamConfig.normalizeEndpoint(""))
        assertNull(RemoteStreamConfig.normalizeEndpoint(null))
    }

    @Test
    fun `a configured but disabled host is not usable`() {
        // Both halves matter: the router skips the remote branch entirely unless this is true,
        // which is what keeps an unconfigured install from waiting on a host nobody set up.
        assertFalse(RemoteStreamConfig.Config("ws://host:1", enabled = false).isUsable)
        assertFalse(RemoteStreamConfig.Config("", enabled = true).isUsable)
        assertTrue(RemoteStreamConfig.Config("ws://host:1", enabled = true).isUsable)
    }
}
