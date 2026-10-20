package dev.autobridge.core.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentActivityStoreTest {
    private fun browser(data: String) =
        RecentActivityStore.Entry(RecentActivityStore.Kind.BROWSER, "title", data = data, timestampMs = 1L)

    @Test fun withoutRemovesTheEntrySharingKindAndData() {
        val a = browser("https://a.example/")
        val b = browser("https://b.example/")
        assertEquals(listOf(b), RecentActivityStore.without(listOf(a, b), a.copy(timestampMs = 99L)))
    }

    @Test fun withoutFallsBackToTitleAndSeparatesKinds() {
        val media = RecentActivityStore.Entry(RecentActivityStore.Kind.MEDIA, "Song")
        val agent = RecentActivityStore.Entry(RecentActivityStore.Kind.AGENT, "Song")
        assertEquals(listOf(agent), RecentActivityStore.without(listOf(media, agent), media))
    }

    @Test fun encodeThenDecodeRoundTrips() {
        val list = listOf(browser("https://a.example/"))
        assertEquals(list, RecentActivityStore.decode(RecentActivityStore.encode(list)))
        assertTrue(RecentActivityStore.decode("garbage").isEmpty())
    }
}
