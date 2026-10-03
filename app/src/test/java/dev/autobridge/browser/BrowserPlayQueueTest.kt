package dev.autobridge.browser

import dev.autobridge.browser.BrowserPlayQueue.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The queue's rules and its stored form, without a Context. */
class BrowserPlayQueueTest {
    private val first = Item("https://m.youtube.com/watch?v=aaa", "First", 1_000)
    private val second = Item("https://music.youtube.com/watch?v=bbb", "Second", 2_000)

    @Test fun itemsQueueInTheOrderTheyWereAdded() {
        val one = BrowserPlayQueue.appended(emptyList(), first)
        assertEquals(listOf(first), one)
        assertEquals(listOf(first, second), BrowserPlayQueue.appended(one!!, second))
    }

    @Test fun aPageAlreadyInTheQueueIsRefusedRatherThanAddedTwiceOrMoved() {
        val queued = listOf(first, second)
        assertNull(BrowserPlayQueue.appended(queued, first))
        // Same URL, different title: still the same page.
        assertNull(BrowserPlayQueue.appended(queued, first.copy(title = "renamed", addedAtMs = 9_000)))
        // The queue is untouched by a refused add.
        assertEquals(listOf(first, second), queued)
    }

    @Test fun theStoredFormRoundTrips() {
        val items = listOf(first, second)
        assertEquals(items, BrowserPlayQueue.decode(BrowserPlayQueue.encode(items)))
        assertEquals(emptyList<Item>(), BrowserPlayQueue.decode(BrowserPlayQueue.encode(emptyList())))
    }

    @Test fun titlesWithQuotesAndThaiTextSurviveTheRoundTrip() {
        val awkward = Item("https://m.youtube.com/watch?v=ccc", "เพลง \"ล่าสุด\" & live \\ x", 3_000)
        assertEquals(listOf(awkward), BrowserPlayQueue.decode(BrowserPlayQueue.encode(listOf(awkward))))
    }

    @Test fun anUnreadableStoreYieldsAnEmptyQueueRatherThanThrowing() {
        // Losing a queue is recoverable; a browser that cannot open is not.
        for (raw in listOf(null, "", "   ", "not json", "{\"url\":\"x\"}", "[1,2,3]")) {
            assertEquals("input: $raw", emptyList<Item>(), BrowserPlayQueue.decode(raw))
        }
    }

    @Test fun entriesThatCouldNotBeNavigatedToAreDropped() {
        // Whatever reaches the store, nothing can come back out that ContentAddress would refuse.
        val raw = """
            [{"url":"http://insecure.example","title":"plain http"},
             {"url":"javascript:alert(1)","title":"script"},
             {"url":"","title":"blank"},
             {"url":"https://m.youtube.com/watch?v=aaa","title":"First","addedAt":1000}]
        """.trimIndent()
        assertEquals(listOf(first), BrowserPlayQueue.decode(raw))
    }

    @Test fun aBareHostIsStoredAsTheHttpsAddressItResolvesTo() {
        val decoded = BrowserPlayQueue.decode("""[{"url":"youtube.com","title":"bare"}]""")
        assertEquals(1, decoded.size)
        assertEquals("https://youtube.com", decoded.first().url)
    }

    @Test fun aMissingTitleOrTimestampIsNotAnError() {
        val decoded = BrowserPlayQueue.decode("""[{"url":"https://m.youtube.com/watch?v=aaa"}]""")
        assertEquals(1, decoded.size)
        assertTrue("title should be blank, was '${decoded.first().title}'", decoded.first().title.isEmpty())
        assertEquals(0L, decoded.first().addedAtMs)
    }
}
