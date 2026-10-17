package dev.autobridge.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** The pure IptvEntry -> IptvHistoryStore.Item mapper used to queue a browse grid. */
class RadioHistoryMappingToHistoryItemTest {
    private fun entry(url: String) = IptvEntry(
        id = "rb:$url",
        title = "Cool 93",
        categoryId = IptvCatalogData.ALL_CATEGORY_ID,
        type = IptvEntryType.LIVE,
        url = url,
        logo = "https://logo.example/i.png",
        subtitle = "Thailand • MP3 128 kbps",
        country = "TH"
    )

    @Test fun `mapper sets every queue-relevant field`() {
        val item = entry("https://a.example/stream").toHistoryItem()
        assertEquals("radio-virtual", item.sourceId)
        assertEquals("Cool 93", item.title)
        assertEquals("https://a.example/stream", item.url)
        assertEquals("https://logo.example/i.png", item.logo)
        assertEquals(IptvKind.RADIO, item.kind)
        assertEquals(IptvEntryType.LIVE, item.type)
        assertEquals(IptvPlayback.STREAM, item.playback)
    }

    @Test fun `mapped item survives aroundItems as a playable stream`() {
        val items = listOf(
            entry("https://a.example/s").toHistoryItem(),
            entry("https://b.example/s").toHistoryItem(),
            entry("https://c.example/s").toHistoryItem()
        )
        val queue = IptvChannelQueue.aroundItems(items, items[1]) { false }
        assertNotNull("mapped item must be kept by aroundItems", queue)
        assertEquals(3, queue!!.channels.size)
    }
}
