package dev.autobridge.projection.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class ChromeKeyInputGraphemeTest {
    @Test
    fun latinCountsEachLetter() {
        assertEquals(3, ChromeKeyInputView.graphemesBetween("hello", 1, 4))
    }

    @Test
    fun thaiClusterWithMarksCountsOnce() {
        // ที่ = consonant + vowel + tone mark: one caret step.
        assertEquals(1, ChromeKeyInputView.graphemesBetween("ที่", 0, 3))
        assertEquals(2, ChromeKeyInputView.graphemesBetween("ที่นี่", 0, 6))
    }

    @Test
    fun surrogatePairCountsOnce() {
        val s = "a\uD83D\uDE00b"
        assertEquals(3, ChromeKeyInputView.graphemesBetween(s, 0, s.length))
    }

    @Test
    fun emptyRangeIsZero() {
        assertEquals(0, ChromeKeyInputView.graphemesBetween("abc", 2, 2))
        assertEquals(0, ChromeKeyInputView.graphemesBetween("abc", 3, 1))
    }
}
