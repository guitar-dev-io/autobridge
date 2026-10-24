package dev.autobridge.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserKeyboardTest {

    private fun labels(row: List<BrowserKey>) = row.map { (it as? BrowserKey.Text)?.lower ?: it::class.simpleName }

    // ---------------------------------------------------------------- layout

    @Test
    fun `url layout is numbers, qwerty, and the url action row`() {
        val rows = BrowserKeyboardLayouts.rows(BrowserKeyboardMode.URL, CarKeyboardLanguage.LATIN, shifted = false)
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"), labels(rows[0]))
        assertEquals(listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"), labels(rows[1]))
        assertEquals(listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"), labels(rows[2]))
        assertEquals(
            listOf("Shift", "z", "x", "c", "v", "b", "n", "m", "CursorLeft", "CursorRight"),
            labels(rows[3])
        )
        assertEquals(
            listOf("Language", "www.", ".com", "/", ".", "-", "_", "Backspace", "Space", "Go"),
            labels(rows[4])
        )
    }

    @Test
    fun `search mode swaps the url pieces for punctuation`() {
        val actions = BrowserKeyboardLayouts.rows(BrowserKeyboardMode.SEARCH, CarKeyboardLanguage.LATIN, false).last()
        assertFalse(labels(actions).contains("www."))
        assertFalse(labels(actions).contains(".com"))
        assertTrue(actions.last() is BrowserKey.Go)
    }

    @Test
    fun `every layer keeps shift, cursor keys and a way back to the other language`() {
        for (mode in BrowserKeyboardMode.entries) for (language in CarKeyboardLanguage.entries) for (shifted in listOf(false, true)) {
            val keys = BrowserKeyboardLayouts.rows(mode, language, shifted).flatten()
            for (required in listOf(BrowserKey.Shift, BrowserKey.CursorLeft, BrowserKey.CursorRight, BrowserKey.Language,
                BrowserKey.Backspace, BrowserKey.Space, BrowserKey.Go)) {
                assertTrue("$mode $language shifted=$shifted lacks $required", keys.contains(required))
            }
        }
    }

    @Test
    fun `shift is one-shot, then locks, then turns off`() {
        assertEquals(ShiftState.ONCE, ShiftState.OFF.next())
        assertEquals(ShiftState.LOCKED, ShiftState.ONCE.next())
        assertEquals(ShiftState.OFF, ShiftState.LOCKED.next())
        assertEquals(ShiftState.OFF, ShiftState.ONCE.afterType())
        assertEquals(ShiftState.LOCKED, ShiftState.LOCKED.afterType())
        val q = BrowserKey.Text("q", "Q")
        assertEquals("Q", BrowserKeyboardLayouts.typed(q, ShiftState.ONCE))
        assertEquals("q", BrowserKeyboardLayouts.typed(q, ShiftState.OFF))
        assertEquals(".com", BrowserKeyboardLayouts.typed(BrowserKey.Text(".com"), ShiftState.LOCKED))
    }

    // ---------------------------------------------------------------- text buffer

    @Test
    fun `typing inserts at the cursor`() {
        val buffer = BrowserTextBuffer("helo")
        buffer.moveCursor(-1)
        buffer.insert("l")
        assertEquals("hello", buffer.text)
        assertEquals(4, buffer.cursor)
    }

    @Test
    fun `typing replaces the selection`() {
        val buffer = BrowserTextBuffer("https://old.example", selectAll = true)
        assertTrue(buffer.hasSelection)
        buffer.insert("n")
        assertEquals("n", buffer.text)
        assertEquals(1, buffer.cursor)
    }

    @Test
    fun `backspace deletes the selection, or one code point`() {
        val selected = BrowserTextBuffer("abc", selectAll = true)
        selected.backspace()
        assertEquals("", selected.text)

        val thai = BrowserTextBuffer("ที่")
        thai.backspace()
        assertEquals("ที", thai.text)

        val emoji = BrowserTextBuffer("a😀")
        emoji.backspace()
        assertEquals("a", emoji.text)

        val start = BrowserTextBuffer("abc")
        start.setCursor(0)
        start.backspace()
        assertEquals("abc", start.text)
    }

    @Test
    fun `cursor moves are clamped and collapse a selection first`() {
        val buffer = BrowserTextBuffer("abc", selectAll = true)
        buffer.moveCursor(-1)
        assertFalse(buffer.hasSelection)
        assertEquals(0, buffer.cursor)
        buffer.moveCursor(-5)
        assertEquals(0, buffer.cursor)
        buffer.moveCursor(10)
        assertEquals(3, buffer.cursor)
        buffer.setCursor(99)
        assertEquals(3, buffer.cursor)
        val emoji = BrowserTextBuffer("😀")
        emoji.setCursor(1)
        assertEquals("a tap never lands inside a surrogate pair", 0, emoji.cursor)
    }

    @Test
    fun `a buffer over a field's selection edits that selection`() {
        val buffer = BrowserTextBuffer.withSelection("hello world", 6, 11)
        buffer.insert("there")
        assertEquals("hello there", buffer.text)
        val reversed = BrowserTextBuffer.withSelection("abc", 3, 1)
        reversed.backspace()
        assertEquals("a", reversed.text)
        val clamped = BrowserTextBuffer.withSelection("abc", -1, 99)
        assertEquals(0, clamped.selectionStart)
        assertEquals(3, clamped.selectionEnd)
    }

    @Test
    fun `material style colours each kind of key`() {
        val style = BrowserKeyboardStyle
        assertEquals(style.GO_CAP to style.ON_GO_CAP, style.colours(BrowserKey.Go, ShiftState.OFF))
        assertEquals(style.LETTER_CAP, style.colours(BrowserKey.Text("q", "Q"), ShiftState.OFF).first)
        assertEquals(style.FUNCTION_CAP, style.colours(BrowserKey.Text(".com"), ShiftState.OFF).first)
        assertEquals(style.FUNCTION_CAP, style.colours(BrowserKey.Shift, ShiftState.OFF).first)
        assertEquals(style.SHIFT_ON_CAP, style.colours(BrowserKey.Shift, ShiftState.LOCKED).first)
        assertEquals("Go ›", style.goLabel("Go"))
        assertEquals(style.ON_FUNCTION_CAP, style.colours(BrowserKey.Backspace, ShiftState.OFF).second)
        assertEquals(style.SHIFT_ON_INK, style.colours(BrowserKey.Shift, ShiftState.ONCE).second)
    }

    @Test
    fun `material tokens keep contrast, flat keys and visible pressed state`() {
        val style = BrowserKeyboardStyle
        fun lum(c: Int): Double {
            fun ch(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch((c shr 16) and 255) + 0.7152 * ch((c shr 8) and 255) + 0.0722 * ch(c and 255)
        }
        fun ratio(a: Int, b: Int): Double { val x = lum(a); val y = lum(b); return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05) }
        assertTrue(ratio(style.INK, style.LETTER_CAP) >= 4.5)
        assertTrue(ratio(style.ON_FUNCTION_CAP, style.FUNCTION_CAP) >= 4.5)
        assertTrue(ratio(style.ON_GO_CAP, style.GO_CAP) >= 4.5)
        assertTrue(ratio(style.SHIFT_ON_INK, style.SHIFT_ON_CAP) >= 4.5)
        assertTrue(ratio(style.INK_SECONDARY, style.TRAY) >= 4.5)
        assertTrue(ratio(style.LETTER_CAP, style.TRAY) >= 1.1)
        assertTrue(style.pressedCap(BrowserKey.Go) != style.GO_CAP)
        assertTrue(style.pressedCap(BrowserKey.Text("q", "Q")) != style.LETTER_CAP)
        assertTrue(style.pressedCap(BrowserKey.Backspace) != style.FUNCTION_CAP)
        assertEquals(0f, style.SHADOW_DP)
        assertEquals(12f, style.KEY_RADIUS_DP)
    }

    // ---------------------------------------------------------------- go

    @Test
    fun `go opens an address and searches anything else`() {
        assertEquals("https://youtube.com", BrowserInputResolver.resolveTyped("youtube.com", SearchEngine.GOOGLE))
        assertEquals("https://example.com/a?b=1", BrowserInputResolver.resolveTyped("http://example.com/a?b=1", SearchEngine.GOOGLE))
        assertEquals("https://localhost", BrowserInputResolver.resolveTyped("localhost", SearchEngine.GOOGLE))
        assertTrue(BrowserInputResolver.resolveTyped("weather", SearchEngine.GOOGLE).startsWith("https://www.google.com/search?q="))
        assertTrue(BrowserInputResolver.resolveTyped("bodyslam live", SearchEngine.YOUTUBE).startsWith("https://m.youtube.com/results"))
        assertTrue(BrowserInputResolver.resolveTyped("เพลงใหม่", SearchEngine.GOOGLE).startsWith("https://www.google.com/search?q="))
        assertNull(BrowserInputResolver.addressOf("javascript:alert(1)"))
        assertNotNull(BrowserInputResolver.addressOf("m.youtube.com/watch?v=1"))
    }

    // ---------------------------------------------------------------- geometry

    private data class Screen(val name: String, val width: Float, val height: Float, val density: Float)

    private val screens = listOf(
        Screen("DHU 1024x600", 1024f, 600f, 1f),
        Screen("800x480", 800f, 480f, 1f),
        Screen("1280x720", 1280f, 720f, 1.33f),
        Screen("1920x1080", 1920f, 1080f, 2f),
        Screen("1920x720 wide", 1920f, 720f, 1.5f),
        Screen("1080x1920 portrait", 1080f, 1920f, 2.5f),
    )

    @Test
    fun `the keyboard fits every head unit, leaving the page above it`() {
        for (screen in screens) for (mode in BrowserKeyboardMode.entries) for (language in CarKeyboardLanguage.entries) {
            val area = Box(0f, 0f, screen.width, screen.height)
            val rows = BrowserKeyboardLayouts.rows(mode, language, shifted = false)
            val g = BrowserKeyboardGeometry.create(area, screen.density, rows)
            val label = "${screen.name} $mode $language"
            assertTrue("$label tray inside the area", g.tray.top >= area.top && g.tray.bottom <= area.bottom + 0.5f)
            assertTrue("$label leaves page above", g.tray.height <= area.height * BrowserKeyboardGeometry.TIGHT_SHARE + 1f)
            assertTrue("$label keys at most 56dp", g.keyHeight <= 56f * screen.density + 0.01f)
            assertTrue("$label keys at least 28px", g.keyHeight >= 28f)
            assertEquals(rows.flatten().size, g.keys.size)
            g.keys.forEach { (key, box) ->
                assertTrue("$label $key inside tray", box.left >= g.tray.left - 0.5f && box.right <= g.tray.right + 0.5f &&
                    box.top >= g.tray.top && box.bottom <= g.tray.bottom + 0.5f)
                assertTrue("$label $key has width", box.width > 10f)
            }
            for (i in g.keys.indices) for (j in i + 1 until g.keys.size) {
                val a = g.keys[i].second
                val b = g.keys[j].second
                val overlap = a.left < b.right - 0.5f && b.left < a.right - 0.5f && a.top < b.bottom - 0.5f && b.top < a.bottom - 0.5f
                assertFalse("$label keys overlap: ${g.keys[i].first} ${g.keys[j].first}", overlap)
            }
            assertTrue(g.field.right <= g.hide.left)
            assertTrue(g.clear.left >= g.field.left && g.clear.right <= g.field.right + 0.5f)
        }
    }

    @Test
    fun `a tap finds the key under it`() {
        val rows = BrowserKeyboardLayouts.rows(BrowserKeyboardMode.URL, CarKeyboardLanguage.LATIN, false)
        val g = BrowserKeyboardGeometry.create(Box(0f, 0f, 1024f, 600f), 1f, rows)
        g.keys.forEach { (key, box) -> assertEquals(key, g.keyAt(box.centerX, box.centerY)) }
        assertNull(g.keyAt(g.field.centerX, g.field.centerY))
    }

    @Test
    fun `letter keys share one width across rows`() {
        val rows = BrowserKeyboardLayouts.rows(BrowserKeyboardMode.URL, CarKeyboardLanguage.LATIN, false)
        val g = BrowserKeyboardGeometry.create(Box(0f, 0f, 1280f, 720f), 1.33f, rows)
        val q = g.keys.first { (it.first as? BrowserKey.Text)?.lower == "q" }.second
        val a = g.keys.first { (it.first as? BrowserKey.Text)?.lower == "a" }.second
        val z = g.keys.first { (it.first as? BrowserKey.Text)?.lower == "z" }.second
        assertEquals(q.width, a.width, 0.01f)
        assertEquals(q.width, z.width, 0.01f)
    }
}
