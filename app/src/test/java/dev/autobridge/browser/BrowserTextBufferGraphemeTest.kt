package dev.autobridge.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserTextBufferGraphemeTest {
    @Test
    fun thaiClusterIsOneStepEachWay() {
        // ที่ = consonant + vowel + tone mark (3 code points), then นี่ (3 more).
        val buffer = BrowserTextBuffer("ที่นี่")
        buffer.moveCursorByGrapheme(-1)
        assertEquals(3, buffer.cursor)
        buffer.moveCursorByGrapheme(-1)
        assertEquals(0, buffer.cursor)
        buffer.moveCursorByGrapheme(1)
        assertEquals(3, buffer.cursor)
    }

    @Test
    fun emojiSurrogatePairIsOneStep() {
        val text = "a\uD83D\uDE00b"
        val buffer = BrowserTextBuffer(text)
        buffer.moveCursorByGrapheme(-1)
        assertEquals(3, buffer.cursor)
        buffer.moveCursorByGrapheme(-1)
        assertEquals(1, buffer.cursor)
        buffer.moveCursorByGrapheme(1)
        assertEquals(3, buffer.cursor)
    }

    @Test
    fun stopsAtBothEnds() {
        val buffer = BrowserTextBuffer("ab")
        buffer.moveCursorByGrapheme(1)
        assertEquals(2, buffer.cursor)
        buffer.moveCursorByGrapheme(-1)
        buffer.moveCursorByGrapheme(-1)
        buffer.moveCursorByGrapheme(-1)
        assertEquals(0, buffer.cursor)

        val empty = BrowserTextBuffer()
        empty.moveCursorByGrapheme(-1)
        empty.moveCursorByGrapheme(1)
        assertEquals(0, empty.cursor)
    }

    @Test
    fun selectionCollapsesTowardTheMove() {
        val left = BrowserTextBuffer("hello", selectAll = true)
        left.moveCursorByGrapheme(-1)
        assertEquals(0, left.cursor)
        assertEquals(false, left.hasSelection)

        val right = BrowserTextBuffer.withSelection("hello", 1, 3)
        right.moveCursorByGrapheme(1)
        assertEquals(3, right.cursor)
        assertEquals(false, right.hasSelection)
    }
}
