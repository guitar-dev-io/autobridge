package dev.autobridge.radio

import dev.autobridge.iptv.ChannelQueue
import dev.autobridge.iptv.IptvChannelQueue
import dev.autobridge.iptv.IptvEntryType
import dev.autobridge.iptv.IptvHistoryStore
import dev.autobridge.iptv.IptvKind
import dev.autobridge.iptv.IptvPlayback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone queue-build rule the repository's `playFromItems` delegates to.
 *
 * The repository calls [IptvChannelQueue.aroundItems] with the live dead-stream predicate
 * (`IptvHealthStore.broken(url) != null`, never `{ false }`), then maps the resulting
 * [ChannelQueue] into [RadioStation]s. These tests exercise that pure core directly: the null case
 * (nothing to step to) plays the anchor alone, the non-null case maps every channel and carries the
 * start index, and a dead channel is honoured by being dropped — which shifts the alignment.
 */
class RadioQueueBuildTest {

    private fun item(url: String, title: String = url) = IptvHistoryStore.Item(
        sourceId = "radio-virtual",
        title = title,
        url = url,
        type = IptvEntryType.LIVE,
        kind = IptvKind.RADIO,
        playback = IptvPlayback.STREAM,
    )

    /** The mapping `playFromItems` applies to each queued channel. */
    private fun toStations(cq: ChannelQueue): List<RadioStation> = cq.channels.map { qc ->
        RadioStation(RadioSourceType.INTERNET_RADIO, id = qc.url, title = qc.title, streamUrl = qc.url)
    }

    @Test fun `a single item has no queue so the anchor plays alone`() {
        val anchor = item("http://only")
        val cq = IptvChannelQueue.aroundItems(listOf(anchor), anchor, isDead = { false })
        assertNull(cq)
        // playFromItems' null branch: queue = listOf(anchor), startIndex 0.
        val fallback = RadioStation(
            RadioSourceType.INTERNET_RADIO, id = anchor.url, title = anchor.title, streamUrl = anchor.url,
        )
        assertEquals(listOf("http://only"), listOf(fallback).map { it.streamUrl })
    }

    @Test fun `a multi-item list builds a mapped queue that carries the start index`() {
        val items = listOf(item("http://a", "A"), item("http://b", "B"), item("http://c", "C"))
        val cq = requireNotNull(
            IptvChannelQueue.aroundItems(items, items[1], isDead = { false })
        )
        val queue = toStations(cq)

        assertEquals(listOf("http://a", "http://b", "http://c"), queue.map { it.streamUrl })
        assertEquals(listOf("A", "B", "C"), queue.map { it.title })
        // Anchor b is index 1, and the start index is carried through unchanged.
        assertEquals(1, cq.startIndex)
        assertEquals("http://b", queue[cq.startIndex].streamUrl)
        assertTrue(queue.all { it.sourceType == RadioSourceType.INTERNET_RADIO })
    }

    @Test fun `the isDead predicate is honoured, not ignored`() {
        val items = listOf(item("http://a", "A"), item("http://dead", "Dead"), item("http://c", "C"))
        // Mark the middle channel dead. It must be dropped (not kept as if isDead were { false }),
        // which collapses the queue to A and C and realigns the anchor index.
        val isDead = { url: String -> url == "http://dead" }
        val cq = requireNotNull(IptvChannelQueue.aroundItems(items, items[2], isDead))
        val queue = toStations(cq)

        assertEquals(listOf("http://a", "http://c"), queue.map { it.streamUrl })
        assertEquals("anchor c realigns to index 1 after the dead one is dropped", 1, cq.startIndex)
        assertEquals("http://c", queue[cq.startIndex].streamUrl)
    }

    @Test fun `a dead anchor is still kept because the user chose it`() {
        val items = listOf(item("http://a"), item("http://chosen"), item("http://c"))
        // The chosen anchor itself reports dead, but the other two are live: the anchor is kept
        // (the driver chose it) and the queue is built around it.
        val isDead = { url: String -> url == "http://chosen" }
        val cq = requireNotNull(IptvChannelQueue.aroundItems(items, items[1], isDead))
        val queue = toStations(cq)

        assertEquals(listOf("http://a", "http://chosen", "http://c"), queue.map { it.streamUrl })
        assertEquals("http://chosen", queue[cq.startIndex].streamUrl)
    }
}
