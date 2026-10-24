package dev.autobridge.browser

import android.content.ContextWrapper
import android.content.res.Resources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Live mode (the desktop browser types each key into Chrome): every key reports what it did,
 * even where the echo buffer cannot follow, and the built-in browser's non-live mode reports
 * nothing. Uses the mockable android.jar (strings come back null, geometry is pure).
 */
class CarSurfaceKeyboardLiveTest {
    private val area = Box(0f, 0f, 1280f, 720f)
    private val density = 1f

    /** Supplies the key labels the keyboard resolves once; Context.getString itself is final. */
    @Suppress("DEPRECATION")
    private class LabelContext : ContextWrapper(null) {
        private val labels = object : Resources(null, null, null) {
            override fun getString(id: Int): String = "label"
        }

        override fun getResources(): Resources = labels
    }

    private fun board(live: Boolean) = CarSurfaceKeyboard(
        LabelContext(),
        CarSurfaceKeyboard.Target.FIELD,
        seed = "",
        mode = BrowserKeyboardMode.SEARCH,
        hint = "hint",
        live = live,
    )

    private fun CarSurfaceKeyboard.tapKey(match: (BrowserKey) -> Boolean): CarSurfaceKeyboard.Result {
        val box = geometry(area, density).keys.firstOrNull { match(it.first) }?.second
        assertNotNull("key not in layout", box)
        return onTap(box!!.centerX, box.centerY, area, density, 0L)
    }

    @Test
    fun backspaceOnEmptyBufferStillDeletesOnThePage() {
        val board = board(live = true)
        val result = board.tapKey { it is BrowserKey.Backspace }
        assertEquals(CarSurfaceKeyboard.Result.Changed(pageMutated = true), result)
        assertEquals(CarSurfaceKeyboard.Edit.DeletedBack, board.lastEdit)
    }

    @Test
    fun cursorKeysReportAtTheBufferEnds() {
        val board = board(live = true)
        assertEquals(CarSurfaceKeyboard.Result.Changed(pageMutated = true), board.tapKey { it is BrowserKey.CursorLeft })
        assertEquals(CarSurfaceKeyboard.Edit.Cursor(-1), board.lastEdit)
        assertEquals(CarSurfaceKeyboard.Result.Changed(pageMutated = true), board.tapKey { it is BrowserKey.CursorRight })
        assertEquals(CarSurfaceKeyboard.Edit.Cursor(1), board.lastEdit)
    }

    @Test
    fun letterIsInsertedAndEchoed() {
        val board = board(live = true)
        var typed = ""
        val result = board.tapKey { key -> (key is BrowserKey.Text).also { if (it) typed = (key as BrowserKey.Text).lower } }
        assertEquals(CarSurfaceKeyboard.Result.Changed(pageMutated = true), result)
        val edit = board.lastEdit as CarSurfaceKeyboard.Edit.Inserted
        assertEquals(typed, edit.text)
        assertEquals(typed, board.buffer.text)
        assertEquals(CarSurfaceKeyboard.Edit.Inserted(" "), board.run { tapKey { it is BrowserKey.Space }; lastEdit })
    }

    @Test
    fun clearReportsCleared() {
        val board = board(live = true)
        board.tapKey { it is BrowserKey.Text }
        board.lastEdit
        val clear = board.geometry(area, density).clear
        val result = board.onTap(clear.centerX, clear.centerY, area, density, 0L)
        assertEquals(CarSurfaceKeyboard.Result.Changed(pageMutated = true), result)
        assertEquals(CarSurfaceKeyboard.Edit.Cleared, board.lastEdit)
        assertEquals("", board.buffer.text)
    }

    @Test
    fun lastEditIsReadOnce() {
        val board = board(live = true)
        board.tapKey { it is BrowserKey.Backspace }
        assertNotNull(board.lastEdit)
        assertNull(board.lastEdit)
    }

    @Test
    fun fieldTapDoesNothingInLiveMode() {
        val board = board(live = true)
        val field = board.geometry(area, density).field
        assertEquals(CarSurfaceKeyboard.Result.None, board.onTap(field.left + 4f, field.centerY, area, density, 0L))
        assertNull(board.lastEdit)
    }

    @Test
    fun layerKeysAndGoReportNoEdit() {
        val board = board(live = true)
        board.tapKey { it is BrowserKey.Shift }
        assertNull(board.lastEdit)
        assertEquals(true, board.tapKey { it is BrowserKey.Go } is CarSurfaceKeyboard.Result.Commit)
        assertNull(board.lastEdit)
    }

    @Test
    fun nonLiveModeNeverReportsAnEdit() {
        val board = board(live = false)
        board.tapKey { it is BrowserKey.Text }
        assertNull(board.lastEdit)
        board.tapKey { it is BrowserKey.Backspace }
        assertNull(board.lastEdit)
        // Built-in browser behaviour unchanged: a cursor key at the end does not mutate the page.
        assertEquals(CarSurfaceKeyboard.Result.Changed(pageMutated = false), board.tapKey { it is BrowserKey.CursorLeft })
        assertNull(board.lastEdit)
    }
}
