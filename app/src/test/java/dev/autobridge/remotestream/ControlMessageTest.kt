package dev.autobridge.remotestream

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire contract with a stream host.
 *
 * A host is written against this format by someone who cannot read this code, so the exact
 * field names and the exact shape of the seek message are asserted rather than left to a round
 * trip that would pass just as well with different names on both sides.
 */
class ControlMessageTest {

    @Test
    fun `a seek is the shape the spec gives`() {
        val json = JSONObject(ControlMessage.Playback(ControlMessage.Playback.Action.SEEK, 120_000L).encode())
        assertEquals("playback", json.getString("type"))
        assertEquals("seek", json.getString("action"))
        assertEquals(120_000L, json.getLong("positionMs"))
    }

    @Test
    fun `play and pause carry no position`() {
        val json = JSONObject(ControlMessage.Playback(ControlMessage.Playback.Action.PLAY).encode())
        assertEquals("play", json.getString("action"))
        assertTrue(!json.has("positionMs"))
    }

    @Test
    fun `every message type round trips`() {
        listOf(
            ControlMessage.Playback(ControlMessage.Playback.Action.PAUSE),
            ControlMessage.Playback(ControlMessage.Playback.Action.SEEK, 42L),
            ControlMessage.Keyboard("hello world", submit = true),
            ControlMessage.Navigation(ControlMessage.Navigation.Action.SELECT),
            ControlMessage.Navigation(ControlMessage.Navigation.Action.BACK),
            ControlMessage.Scroll(0, -240),
            ControlMessage.Open("https://example.com/x", 5_000L)
        ).forEach { message ->
            assertEquals(message.toString(), message, ControlMessage.decode(message.encode()))
        }
    }

    @Test
    fun `an unknown type is ignored rather than fatal`() {
        // A host is free to extend the protocol; an unrecognised message must not drop the link.
        assertNull(ControlMessage.decode("""{"type":"hologram","action":"on"}"""))
        assertNull(ControlMessage.decode("not json"))
        assertNull(ControlMessage.decode(null))
    }

    @Test
    fun `an unknown action within a known type is ignored`() {
        assertNull(ControlMessage.decode("""{"type":"playback","action":"rewind"}"""))
    }

    @Test
    fun `host status is decoded`() {
        val status = HostStatusMessage.decode(
            """{"type":"status","state":"playing","title":"Clip","positionMs":1000,"durationMs":60000}"""
        )
        assertEquals("playing", status?.state)
        assertEquals("Clip", status?.title)
        assertEquals(1_000L, status?.positionMs)
        assertEquals(60_000L, status?.durationMs)
        assertNull(status?.error)
    }

    @Test
    fun `a control message is not mistaken for a host status`() {
        assertNull(HostStatusMessage.decode("""{"type":"playback","action":"play"}"""))
    }
}
