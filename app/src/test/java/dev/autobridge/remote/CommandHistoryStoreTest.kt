package dev.autobridge.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandHistoryStoreTest {
    private fun entry(id: String, ts: Long) = CommandHistoryStore.Entry(
        commandId = id, type = CommandType.SEARCH_WEB, payload = "q", label = "Search q", success = true, timestamp = ts
    )

    @Test fun withoutDropsOneOfTwoEntriesSharingAnIdByTimestamp() {
        val newer = entry("same", 20L)
        val older = entry("same", 10L)
        val other = entry("other", 5L)
        assertEquals(listOf(older, other), CommandHistoryStore.without(listOf(newer, older, other), newer))
        assertEquals(listOf(newer, other), CommandHistoryStore.without(listOf(newer, older, other), older))
    }

    @Test fun withoutKeepsTheListWhenNothingMatches() {
        val list = listOf(entry("a", 1L), entry("b", 2L))
        assertEquals(list, CommandHistoryStore.without(list, entry("c", 3L)))
    }

    @Test fun encodeThenDecodeRoundTrips() {
        val list = listOf(entry("a", 1L), entry("b", 2L))
        assertEquals(list, CommandHistoryStore.decode(CommandHistoryStore.encode(list)))
        assertTrue(CommandHistoryStore.decode("garbage").isEmpty())
    }
}
