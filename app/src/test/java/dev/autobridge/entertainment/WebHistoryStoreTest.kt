package dev.autobridge.entertainment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebHistoryStoreTest {
    private val a = WebHistoryEntry("A", "https://a.example/", 3L)
    private val b = WebHistoryEntry("B", "https://b.example/", 2L)
    private val c = WebHistoryEntry("C", "https://c.example/", 1L)

    @Test fun withoutRemovesOnlyThatUrlAndKeepsOrder() {
        assertEquals(listOf(a, c), WebHistoryStore.without(listOf(a, b, c), b.url))
        assertEquals(listOf(a, b, c), WebHistoryStore.without(listOf(a, b, c), "https://missing.example/"))
    }

    @Test fun removingTheLastEntryLeavesAnEmptyList() {
        assertTrue(WebHistoryStore.without(listOf(a), a.url).isEmpty())
    }

    @Test fun encodeThenDecodeRoundTrips() {
        val entries = listOf(a, b, c)
        assertEquals(entries, WebHistoryStore.decode(WebHistoryStore.encode(entries)))
    }

    @Test fun decodeOfGarbageOrNothingIsEmpty() {
        assertTrue(WebHistoryStore.decode(null).isEmpty())
        assertTrue(WebHistoryStore.decode("not json").isEmpty())
    }
}
