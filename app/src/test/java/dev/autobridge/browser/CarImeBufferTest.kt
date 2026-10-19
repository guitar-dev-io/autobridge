package dev.autobridge.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The new IME<->buffer reconciliation rule [applyImeEdit] against [BrowserTextBuffer], covering the
 * desync risk a real IME (Gboard, its own model) poses directly. Commit is a selection-replace (not
 * a wholesale replace, not a blind append); DeleteBefore is N surrogate-safe backspaces that never
 * underflow past empty. Seeds mirror `CarSurfaceKeyboard`'s buffer construction
 * (`selectAll = target == ADDRESS && seed.isNotEmpty()`). Styled on [BrowserKeyboardTest].
 */
class CarImeBufferTest {

    @Test
    fun `address first commit replaces the selected URL and collapses the cursor`() {
        val buffer = BrowserTextBuffer("example.com", selectAll = true)
        applyImeEdit(buffer, ImeEdit.Commit("x"))
        assertEquals("x", buffer.text)
        assertEquals(1, buffer.cursor)
        assertEquals(buffer.selectionStart, buffer.selectionEnd)
    }

    @Test
    fun `address second commit appends at the cursor`() {
        val buffer = BrowserTextBuffer("example.com", selectAll = true)
        applyImeEdit(buffer, ImeEdit.Commit("x"))
        applyImeEdit(buffer, ImeEdit.Commit("y"))
        assertEquals("xy", buffer.text)
    }

    @Test
    fun `field backspace deletes to empty one character at a time`() {
        val buffer = BrowserTextBuffer("ab", selectAll = false)
        applyImeEdit(buffer, ImeEdit.DeleteBefore(1))
        assertEquals("a", buffer.text)
        applyImeEdit(buffer, ImeEdit.DeleteBefore(1))
        assertEquals("", buffer.text)
    }

    @Test
    fun `thai multi char commits append verbatim without composing loss`() {
        val buffer = BrowserTextBuffer("", selectAll = false)
        applyImeEdit(buffer, ImeEdit.Commit("ส"))
        applyImeEdit(buffer, ImeEdit.Commit("วัสดี"))
        assertEquals("สวัสดี", buffer.text)
    }

    @Test
    fun `a word replacement commit inserts at the collapsed cursor`() {
        val buffer = BrowserTextBuffer("world", selectAll = false)
        // cursor is collapsed at the end of "world"
        applyImeEdit(buffer, ImeEdit.Commit("s"))
        assertEquals("worlds", buffer.text)
    }

    @Test
    fun `delete before never underflows past empty`() {
        val buffer = BrowserTextBuffer("abc", selectAll = false)
        applyImeEdit(buffer, ImeEdit.DeleteBefore(100))
        assertEquals("", buffer.text)
        assertEquals(0, buffer.cursor)
    }
}
