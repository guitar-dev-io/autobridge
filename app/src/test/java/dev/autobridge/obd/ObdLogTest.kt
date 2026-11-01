package dev.autobridge.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ObdLogTest {
    @Before fun reset() = ObdLog.clear()

    @Test fun fordRequestsKeepTheirOwnList() {
        ObdLog.add("221674", "NO DATA\r")
        repeat(200) { ObdLog.add("010C", "41 0C 0B B8") }
        val text = ObdLog.snapshot("ENH", "RECENT")
        val enhanced = text.substringAfter("ENH").substringBefore("RECENT")
        assertTrue(enhanced.contains("221674  ->  NO DATA"))
        // The shared list only keeps the latest 60 lines, none of them the Ford one.
        assertFalse(text.substringAfter("RECENT").contains("221674"))
    }

    @Test fun anEmptyReplyIsWrittenOut() {
        ObdLog.add("0105", "  \r")
        assertTrue(ObdLog.snapshot("E", "R").contains("0105  ->  (nothing)"))
    }

    @Test fun theListsAreBounded() {
        repeat(500) { ObdLog.add("22$it", "x") }
        val lines = ObdLog.snapshot("E", "R").lines().filter { it.contains("->") }
        // 40 enhanced + 60 shown from the shared list.
        assertEquals(100, lines.size)
    }
}
