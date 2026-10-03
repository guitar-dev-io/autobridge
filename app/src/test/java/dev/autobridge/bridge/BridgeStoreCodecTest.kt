package dev.autobridge.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pending-send and last-session codecs.
 *
 * The behaviour worth pinning is the tolerance: these two values are read on the car-connect
 * path, which is the worst possible moment to throw. A corrupt preference must come back as
 * "nothing pending", never as an exception on the head unit.
 */
class BridgeStoreCodecTest {

    @Test
    fun `a source survives a round trip`() {
        val source = BridgeSource(
            url = "https://m.youtube.com/watch?v=abc",
            title = "A video",
            positionMs = 754_000L,
            mimeType = "video/mp4",
            origin = BridgeSource.Origin.SHARE
        )
        assertEquals(source, BridgeStore.decodeSource(BridgeStore.encodeSource(source)))
    }

    @Test
    fun `a source with no mime type round trips as null`() {
        val source = BridgeSource("https://example.com/x", "T")
        val decoded = BridgeStore.decodeSource(BridgeStore.encodeSource(source))
        assertNull(decoded?.mimeType)
        assertEquals("https://example.com/x", decoded?.url)
    }

    @Test
    fun `garbage decodes to nothing rather than throwing`() {
        assertNull(BridgeStore.decodeSource("{not json"))
        assertNull(BridgeStore.decodeSource(""))
        assertNull(BridgeStore.decodeSource(null))
        assertNull(BridgeStore.decodeSnapshot("[]"))
    }

    @Test
    fun `an entry whose url is unusable is dropped`() {
        // Written by an older build, or corrupted. Either way it must not become a navigation.
        assertNull(BridgeStore.decodeSource("""{"url":"javascript:alert(1)"}"""))
        assertNull(BridgeStore.decodeSource("""{"url":""}"""))
    }

    @Test
    fun `a snapshot survives a round trip`() {
        val snapshot = BridgeStore.Snapshot(
            source = BridgeSource("https://example.com/v.mp4", "Clip"),
            engine = EngineKind.NATIVE,
            positionMs = 12_000L,
            savedAtMs = 1_700_000_000_000L
        )
        val decoded = BridgeStore.decodeSnapshot(BridgeStore.encodeSnapshot(snapshot))
        assertEquals(snapshot.engine, decoded?.engine)
        assertEquals(snapshot.positionMs, decoded?.positionMs)
        assertEquals(snapshot.savedAtMs, decoded?.savedAtMs)
        assertEquals(snapshot.source.url, decoded?.source?.url)
    }

    @Test
    fun `an unknown engine name falls back rather than failing the restore`() {
        val raw = """{"source":{"url":"https://example.com/"},"engine":"QUANTUM","positionMs":5}"""
        assertEquals(EngineKind.BROWSER, BridgeStore.decodeSnapshot(raw)?.engine)
    }
}
