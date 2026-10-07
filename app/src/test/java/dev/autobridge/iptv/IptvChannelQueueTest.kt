package dev.autobridge.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IptvChannelQueueTest {
    private fun live(n: Int, url: String = "https://tv.example/$n.m3u8") =
        IptvEntry(id = "$n", title = "Ch $n", categoryId = "c", type = IptvEntryType.LIVE, url = url)

    private fun item(n: Int, playback: IptvPlayback = IptvPlayback.STREAM) = IptvHistoryStore.Item(
        "s", "Ch $n", "https://tv.example/$n.m3u8", IptvEntryType.LIVE, IptvKind.TV, 0L, playback
    )

    @Test
    fun queueStartsAtTheOpenedChannelAndSkipsDeadOnes() {
        val entries = (0 until 5).map { live(it) }
        val queue = IptvChannelQueue.around(entries, entries[3]) { it.endsWith("/1.m3u8") }!!
        assertEquals(listOf("Ch 0", "Ch 2", "Ch 3", "Ch 4"), queue.channels.map { it.title })
        assertEquals(2, queue.startIndex)
    }

    @Test
    fun theOpenedChannelStaysEvenWhenTheCheckCalledItDead() {
        val entries = (0 until 3).map { live(it) }
        val queue = IptvChannelQueue.around(entries, entries[1]) { true }
        // Only the chosen channel is left: nothing to step to, so it plays on its own.
        assertNull(queue)
    }

    @Test
    fun webPagesAndFoldersAreNotQueued() {
        val web = live(1).copy(playback = IptvPlayback.WEB_PAGE)
        val folder = live(2, url = "").copy(type = IptvEntryType.SERIES)
        val entries = listOf(live(0), web, folder, live(3))
        val queue = IptvChannelQueue.around(entries, entries[3]) { false }!!
        assertEquals(listOf("Ch 0", "Ch 3"), queue.channels.map { it.title })
        assertNull(IptvChannelQueue.around(entries, web) { false })
    }

    @Test
    fun aLongCategoryIsWindowedAroundTheChannel() {
        val entries = (0 until 1000).map { live(it) }
        val queue = IptvChannelQueue.around(entries, entries[500]) { false }!!
        assertEquals(IptvChannelQueue.WINDOW_EACH_SIDE * 2 + 1, queue.channels.size)
        assertEquals("Ch 500", queue.channels[queue.startIndex].title)
        val first = IptvChannelQueue.around(entries, entries[0]) { false }!!
        assertEquals(0, first.startIndex)
    }

    @Test
    fun quickRowsAreFavouritesThenRecentWithoutRepeatsOrDeadOnes() {
        val quick = IptvChannelQueue.quick(
            favorites = listOf(item(1), item(2)),
            recent = listOf(item(2), item(3), item(4), item(5), item(6)),
            isDead = { it.endsWith("/4.m3u8") },
        )
        assertEquals(listOf("Ch 1", "Ch 2", "Ch 3", "Ch 5"), quick.map { it.title })
    }

    @Test
    fun rememberedItemsQueueTheSameWay() {
        val items = listOf(item(1), item(2, IptvPlayback.WEB_PAGE), item(3))
        val queue = IptvChannelQueue.aroundItems(items, items[2]) { false }!!
        assertEquals(listOf("Ch 1", "Ch 3"), queue.channels.map { it.title })
        assertEquals(1, queue.startIndex)
    }
}
