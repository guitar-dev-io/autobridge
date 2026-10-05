package dev.autobridge.logging

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sink is what carries the in-memory ring to disk, so a crash on a head unit leaves something
 * behind. Two properties matter and neither needs Android:
 *
 * - every entry reaches it, in order, so the file is the same story the screens show;
 * - a sink that throws cannot take logging down with it. The sink writes to a file, and a file
 *   write fails for reasons that have nothing to do with the thing being logged - a full disk, a
 *   locked directory. Losing the log at exactly the moment something is going wrong would be the
 *   worst possible time for it.
 */
class StructuredLogSinkTest {
    @After fun tearDown() {
        StructuredLog.sink = null
        StructuredLog.clear()
    }

    @Test fun everyEntryReachesTheSinkInOrder() {
        val seen = mutableListOf<String>()
        StructuredLog.sink = { seen += "${it.level.name}/${it.tag}:${it.message}" }

        StructuredLog.i("A", "one")
        StructuredLog.w("B", "two")
        StructuredLog.e("C", "three")

        assertEquals(listOf("INFO/A:one", "WARN/B:two", "ERROR/C:three"), seen)
    }

    @Test fun aSinkThatThrowsDoesNotStopLoggingOrLoseTheEntry() {
        StructuredLog.clear()
        StructuredLog.sink = { error("disk full") }

        StructuredLog.i("Tag", "still recorded")

        val recent = StructuredLog.recent()
        assertEquals(1, recent.size)
        assertEquals("still recorded", recent.single().message)
    }

    @Test fun clearingTheSinkStopsDelivery() {
        var count = 0
        StructuredLog.sink = { count++ }
        StructuredLog.i("Tag", "delivered")
        StructuredLog.sink = null
        StructuredLog.i("Tag", "not delivered")

        assertEquals(1, count)
        assertTrue(StructuredLog.recent().size >= 2)
    }
}
