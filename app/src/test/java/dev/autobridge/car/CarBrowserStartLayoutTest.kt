package dev.autobridge.car

import dev.autobridge.entertainment.WebBookmark
import dev.autobridge.entertainment.WebHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarBrowserStartLayoutTest {
    private fun history(vararg urls: String): List<WebHistoryEntry> =
        urls.mapIndexed { index, url -> WebHistoryEntry("Title $index", url, index.toLong()) }

    private fun bookmarks(count: Int): List<WebBookmark> =
        (0 until count).map { WebBookmark("Bookmark $it", "https://b$it.example.com") }

    /** total cells = search(1) + fixed + recent + bookmarks, must never exceed the limit. */
    private fun total(fixedCount: Int, result: CarBrowserStartLayout.Result): Int =
        1 + fixedCount + result.recent.size + result.bookmarks.size

    @Test fun emptyHistoryLeavesBookmarksFullRoom() {
        val result = CarBrowserStartLayout.compute(
            limit = 6, fixedCount = 2, liveUrl = null,
            history = emptyList(), bookmarks = bookmarks(10)
        )
        assertTrue(result.recent.isEmpty())
        // limit 6 - search 1 - fixed 2 = 3 bookmark slots, same as before Recent existed.
        assertEquals(3, result.bookmarks.size)
        assertTrue(total(2, result) <= 6)
    }

    @Test fun recentTakesPriorityOverBookmarks() {
        val result = CarBrowserStartLayout.compute(
            limit = 6, fixedCount = 2, liveUrl = null,
            history = history("https://a.example.com/x", "https://b.example.com/y", "https://c.example.com/z", "https://d.example.com/w", "https://e.example.com/v"),
            bookmarks = bookmarks(10)
        )
        assertEquals(3, result.recent.size)
        assertEquals(0, result.bookmarks.size)
        assertTrue(total(2, result) <= 6)
    }

    @Test fun liveUrlIsDeDupedFromRecent() {
        val live = "https://a.example.com/x"
        val result = CarBrowserStartLayout.compute(
            limit = 24, fixedCount = 2, liveUrl = live,
            history = history(live, "https://b.example.com/y", "https://c.example.com/z"),
            bookmarks = emptyList()
        )
        assertEquals(listOf("https://b.example.com/y", "https://c.example.com/z"), result.recent.map { it.url })
    }

    @Test fun tinyRemainingClampsRecent() {
        // limit 4 - search 1 - fixed 2 = 1 remaining slot.
        val result = CarBrowserStartLayout.compute(
            limit = 4, fixedCount = 2, liveUrl = null,
            history = history("https://a.example.com/x", "https://b.example.com/y"),
            bookmarks = bookmarks(5)
        )
        assertEquals(1, result.recent.size)
        assertEquals(0, result.bookmarks.size)
        assertTrue(total(2, result) <= 4)
    }

    @Test fun totalNeverExceedsLimitAcrossSizes() {
        for (limit in 4..24) {
            val result = CarBrowserStartLayout.compute(
                limit = limit, fixedCount = 2, liveUrl = null,
                history = history("https://a/1", "https://b/2", "https://c/3", "https://d/4", "https://e/5"),
                bookmarks = bookmarks(30)
            )
            assertTrue("limit=$limit", total(2, result) <= limit)
        }
    }
}
