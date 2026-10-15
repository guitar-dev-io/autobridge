package dev.autobridge.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

/** The pure Podcasts multi-tag merge: tag-order concatenation + cross-answer dedupe by url. */
class RadioBrowserBrowseMergeTest {
    private fun entry(url: String, title: String = url) = IptvEntry(
        id = url,
        title = title,
        categoryId = IptvCatalogData.ALL_CATEGORY_ID,
        type = IptvEntryType.LIVE,
        url = url
    )

    @Test fun `concatenates answers in tag order`() {
        val merged = RadioBrowserBrowse.mergePodcasts(
            listOf(
                listOf(entry("a"), entry("b")),
                listOf(entry("c")),
                listOf(entry("d"))
            )
        )
        assertEquals(listOf("a", "b", "c", "d"), merged.map { it.url })
    }

    @Test fun `cross-answer dedupe keeps the first occurrence`() {
        val merged = RadioBrowserBrowse.mergePodcasts(
            listOf(
                listOf(entry("a", "talk-a"), entry("b", "talk-b")),
                listOf(entry("b", "news-b"), entry("c", "news-c")),
                listOf(entry("a", "podcast-a"))
            )
        )
        assertEquals(listOf("a", "b", "c"), merged.map { it.url })
        // First occurrence wins: "a" keeps its talk title, "b" keeps its talk title.
        assertEquals("talk-a", merged.first { it.url == "a" }.title)
        assertEquals("talk-b", merged.first { it.url == "b" }.title)
    }

    @Test fun `a single-success-with-failures shape yields the successes`() {
        // Failed tag fetches contribute no answer list, so the merge sees only the successes.
        val merged = RadioBrowserBrowse.mergePodcasts(
            listOf(listOf(entry("x"), entry("y")))
        )
        assertEquals(listOf("x", "y"), merged.map { it.url })
    }

    @Test fun `no answers merges to empty`() {
        assertEquals(emptyList<String>(), RadioBrowserBrowse.mergePodcasts(emptyList()).map { it.url })
    }
}
