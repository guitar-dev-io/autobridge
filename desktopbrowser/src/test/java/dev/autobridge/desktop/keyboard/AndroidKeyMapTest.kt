package dev.autobridge.desktop.keyboard

import android.view.KeyEvent
import dev.autobridge.desktop.input.Keysym
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidKeyMapTest {
    private fun key(keysym: Int, vararg mods: Int, focusMove: Boolean) = KeyAction.Key(keysym, mods, focusMove)

    @Test fun editingKeys() {
        assertEquals(key(Keysym.BACKSPACE, focusMove = false), AndroidKeyMap.map(KeyEvent.KEYCODE_DEL, 0, 0, 0))
        assertEquals(key(Keysym.DELETE, focusMove = false), AndroidKeyMap.map(KeyEvent.KEYCODE_FORWARD_DEL, 0, 0, 0))
        assertEquals(key(Keysym.RETURN, focusMove = true), AndroidKeyMap.map(KeyEvent.KEYCODE_ENTER, 0, '\n'.code, '\n'.code))
        assertEquals(key(Keysym.RETURN, focusMove = true), AndroidKeyMap.map(KeyEvent.KEYCODE_NUMPAD_ENTER, 0, 0, 0))
        assertEquals(key(Keysym.TAB, focusMove = true), AndroidKeyMap.map(KeyEvent.KEYCODE_TAB, 0, '\t'.code, '\t'.code))
        assertEquals(key(Keysym.ESCAPE, focusMove = true), AndroidKeyMap.map(KeyEvent.KEYCODE_ESCAPE, 0, 0, 0))
        assertEquals(key(Keysym.INSERT, focusMove = false), AndroidKeyMap.map(KeyEvent.KEYCODE_INSERT, 0, 0, 0))
        assertEquals(key(Keysym.F1, focusMove = false), AndroidKeyMap.map(KeyEvent.KEYCODE_F1, 0, 0, 0))
        assertEquals(key(Keysym.F12, focusMove = false), AndroidKeyMap.map(KeyEvent.KEYCODE_F12, 0, 0, 0))
    }

    @Test fun navigationKeys() {
        assertEquals(key(Keysym.LEFT, focusMove = false), AndroidKeyMap.map(KeyEvent.KEYCODE_DPAD_LEFT, 0, 0, 0))
        assertEquals(key(Keysym.RIGHT, focusMove = false), AndroidKeyMap.map(KeyEvent.KEYCODE_DPAD_RIGHT, 0, 0, 0))
        assertEquals(key(Keysym.UP, focusMove = true), AndroidKeyMap.map(KeyEvent.KEYCODE_DPAD_UP, 0, 0, 0))
        assertEquals(key(Keysym.DOWN, focusMove = true), AndroidKeyMap.map(KeyEvent.KEYCODE_DPAD_DOWN, 0, 0, 0))
        assertEquals(key(Keysym.HOME, focusMove = false), AndroidKeyMap.map(KeyEvent.KEYCODE_MOVE_HOME, 0, 0, 0))
        assertEquals(key(Keysym.END, focusMove = false), AndroidKeyMap.map(KeyEvent.KEYCODE_MOVE_END, 0, 0, 0))
        assertEquals(key(Keysym.PAGE_UP, focusMove = true), AndroidKeyMap.map(KeyEvent.KEYCODE_PAGE_UP, 0, 0, 0))
        assertEquals(key(Keysym.PAGE_DOWN, focusMove = true), AndroidKeyMap.map(KeyEvent.KEYCODE_PAGE_DOWN, 0, 0, 0))
    }

    @Test fun ctrlC() {
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        assertEquals(key('c'.code, Keysym.CONTROL_L, focusMove = true), AndroidKeyMap.map(KeyEvent.KEYCODE_C, meta, 0, 'c'.code))
    }

    @Test fun ctrlShiftZUsesTheLowercaseBaseCharacter() {
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON
        assertEquals(key('z'.code, Keysym.CONTROL_L, Keysym.SHIFT_L, focusMove = true),
            AndroidKeyMap.map(KeyEvent.KEYCODE_Z, meta, 0, 'Z'.code))
        // No base character: the key's letter.
        assertEquals(key('z'.code, Keysym.CONTROL_L, Keysym.SHIFT_L, focusMove = true),
            AndroidKeyMap.map(KeyEvent.KEYCODE_Z, meta, 0, 0))
        assertEquals(key('5'.code, Keysym.CONTROL_L, focusMove = true),
            AndroidKeyMap.map(KeyEvent.KEYCODE_5, KeyEvent.META_CTRL_ON, 0, 0))
    }

    @Test fun altLeft() {
        val meta = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        assertEquals(key(Keysym.LEFT, Keysym.ALT_L, focusMove = true), AndroidKeyMap.map(KeyEvent.KEYCODE_DPAD_LEFT, meta, 0, 0))
        assertEquals(key('d'.code, Keysym.ALT_L, focusMove = true), AndroidKeyMap.map(KeyEvent.KEYCODE_D, meta, 0, 'd'.code))
    }

    @Test fun shiftTabAndShiftNavigation() {
        assertEquals(key(Keysym.TAB, Keysym.SHIFT_L, focusMove = true),
            AndroidKeyMap.map(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON, '\t'.code, '\t'.code))
        assertEquals(key(Keysym.RIGHT, Keysym.SHIFT_L, focusMove = false),
            AndroidKeyMap.map(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON, 0, 0))
    }

    @Test fun altGrGivesText() {
        val meta = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON
        assertEquals(KeyAction.Text("€"), AndroidKeyMap.map(KeyEvent.KEYCODE_E, meta, '€'.code, 'e'.code))
    }

    @Test fun bareModifiersAreConsumed() {
        for (code in listOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_META_LEFT,
            KeyEvent.KEYCODE_CAPS_LOCK)) {
            assertEquals(KeyAction.Consumed, AndroidKeyMap.map(code, KeyEvent.META_SHIFT_ON, 0, 0))
        }
    }

    @Test fun printableCharactersAreText() {
        assertEquals(KeyAction.Text("a"), AndroidKeyMap.map(KeyEvent.KEYCODE_A, 0, 'a'.code, 'a'.code))
        assertEquals(KeyAction.Text("A"), AndroidKeyMap.map(KeyEvent.KEYCODE_A, KeyEvent.META_SHIFT_ON, 'A'.code, 'a'.code))
        assertEquals(KeyAction.Text(" "), AndroidKeyMap.map(KeyEvent.KEYCODE_SPACE, 0, ' '.code, ' '.code))
        assertEquals(KeyAction.Text("ก"), AndroidKeyMap.map(KeyEvent.KEYCODE_D, 0, 'ก'.code, 'ก'.code))
    }

    @Test fun deadKeysAreConsumedAndUnknownKeysUnhandled() {
        assertEquals(KeyAction.Consumed, AndroidKeyMap.map(KeyEvent.KEYCODE_GRAVE, 0, Int.MIN_VALUE or 0x0300, 0))
        assertEquals(KeyAction.Unhandled, AndroidKeyMap.map(KeyEvent.KEYCODE_VOLUME_UP, 0, 0, 0))
        assertEquals(KeyAction.Unhandled, AndroidKeyMap.map(KeyEvent.KEYCODE_BACK, 0, 0, 0))
        assertEquals(KeyAction.Unhandled, AndroidKeyMap.map(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.META_CTRL_ON, 0, 0))
    }
}
