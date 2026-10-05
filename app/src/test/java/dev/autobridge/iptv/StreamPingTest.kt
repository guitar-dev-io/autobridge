package dev.autobridge.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The decisions [StreamPing] makes before and after the one network call: what an address resolves
 * to, and what a status code means for a channel. Both are pure, so they are pinned down here; the
 * probe itself needs a server and is covered by the hardware pass instead.
 */
class StreamPingTest {

    @Test
    fun `an http address keeps its default port and is probed over http`() {
        val endpoint = StreamPing.endpoint("http://portal.tv/live/1.ts")
        assertEquals("portal.tv", endpoint?.host)
        assertEquals(80, endpoint?.port)
        assertEquals(true, endpoint?.httpLike)
    }

    @Test
    fun `https defaults to 443`() {
        assertEquals(443, StreamPing.endpoint("https://portal.tv/live/1.m3u8")?.port)
    }

    @Test
    fun `an explicit port wins`() {
        val endpoint = StreamPing.endpoint("http://portal.tv:8080/live/1.ts")
        assertEquals("portal.tv", endpoint?.host)
        assertEquals(8080, endpoint?.port)
    }

    @Test
    fun `credentials in the authority are not mistaken for the host`() {
        assertEquals("portal.tv", StreamPing.endpoint("http://user:pw@portal.tv/live")?.host)
    }

    @Test
    fun `an IPv6 literal survives the port split`() {
        val endpoint = StreamPing.endpoint("http://[2001:db8::1]:8080/live/1.ts")
        assertEquals("2001:db8::1", endpoint?.host)
        assertEquals(8080, endpoint?.port)
    }

    @Test
    fun `a non-HTTP stream scheme is probed by connecting to its own port`() {
        val rtmp = StreamPing.endpoint("rtmp://edge.example.com/live/stream")
        assertEquals(1935, rtmp?.port)
        assertEquals(false, rtmp?.httpLike)
        assertEquals(554, StreamPing.endpoint("rtsp://cam.example.com/stream")?.port)
    }

    @Test
    fun `an address nothing can connect to is not an address`() {
        // Multicast answers no connection, so it is reported as not checkable, never as dead.
        assertNull(StreamPing.endpoint("udp://@239.0.0.1:1234"))
        assertNull(StreamPing.endpoint("rtp://239.0.0.1:1234"))
        assertNull(StreamPing.endpoint("/storage/emulated/0/clip.mp4"))
        assertNull(StreamPing.endpoint("http://"))
        assertNull(StreamPing.endpoint(""))
    }

    @Test
    fun `a port outside the range falls back to the scheme's own`() {
        assertEquals(80, StreamPing.endpoint("http://portal.tv:0/live")?.port)
        assertEquals(80, StreamPing.endpoint("http://portal.tv:99999/live")?.port)
    }

    @Test
    fun `a servable status is alive`() {
        assertEquals(StreamPing.Result.Alive(12L), StreamPing.classify(200, 12L))
        // Many providers answer a ranged request with 206, and some with a redirect to a token URL.
        assertEquals(StreamPing.Result.Alive(12L), StreamPing.classify(206, 12L))
        assertEquals(StreamPing.Result.Alive(12L), StreamPing.classify(302, 12L))
    }

    @Test
    fun `a refusal keeps its status, because the status is the diagnosis`() {
        assertEquals(StreamPing.Result.Refused(403, 9L), StreamPing.classify(403, 9L))
        assertEquals(StreamPing.Result.Refused(404, 9L), StreamPing.classify(404, 9L))
        assertEquals(StreamPing.Result.Refused(503, 9L), StreamPing.classify(503, 9L))
    }

    @Test
    fun `each result reads as a row subtitle`() {
        assertEquals("142 ms", StreamPing.describe(StreamPing.Result.Alive(142L)))
        assertEquals("HTTP 403", StreamPing.describe(StreamPing.Result.Refused(403, 20L)))
        assertEquals("Timed out", StreamPing.describe(StreamPing.Result.Unreachable("Timed out")))
        assertEquals("Not checkable", StreamPing.describe(StreamPing.Result.Unsupported))
    }

    @Test
    fun `a fast answer reads as good and a slow one as slow`() {
        assertEquals(StreamPing.Tone.GOOD, StreamPing.tone(StreamPing.Result.Alive(120L)))
        assertEquals(StreamPing.Tone.GOOD, StreamPing.tone(StreamPing.Result.Alive(1_000L)))
        assertEquals(StreamPing.Tone.SLOW, StreamPing.tone(StreamPing.Result.Alive(1_001L)))
    }

    @Test
    fun `a refusal and a silence both read as bad`() {
        assertEquals(StreamPing.Tone.BAD, StreamPing.tone(StreamPing.Result.Refused(403, 20L)))
        assertEquals(StreamPing.Tone.BAD, StreamPing.tone(StreamPing.Result.Unreachable("Timed out")))
    }

    @Test
    fun `an address that cannot be probed is not called dead`() {
        // The middle reading: it is neither an answer nor a refusal, so it must not read as red.
        assertEquals(StreamPing.Tone.SLOW, StreamPing.tone(StreamPing.Result.Unsupported))
    }

    @Test
    fun `forgetting an address that was never checked changes nothing`() {
        StreamPing.forget(listOf("http://portal.tv/live/never-checked.ts", ""))
        assertNull(StreamPing.cached("http://portal.tv/live/never-checked.ts"))
    }

    @Test
    fun `nothing is remembered about an address that was never checked`() {
        assertNull(StreamPing.cached("http://portal.tv/live/never-checked.ts"))
    }
}
