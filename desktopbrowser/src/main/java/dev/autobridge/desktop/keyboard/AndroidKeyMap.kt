package dev.autobridge.desktop.keyboard

import android.view.KeyEvent
import dev.autobridge.desktop.input.Keysym

/** What an Android key event means for the guest. */
sealed class KeyAction {
    /**
     * A key or chord. [focusMove] is set for keys after which the page's focus or content may
     * have moved (Return, Tab, Escape, Up/Down, PgUp/PgDn, any Ctrl/Alt chord): the IME mirror
     * must be reset.
     */
    class Key(val keysym: Int, val modifiers: IntArray, val focusMove: Boolean) : KeyAction() {
        override fun equals(other: Any?): Boolean =
            other is Key && other.keysym == keysym && other.modifiers.contentEquals(modifiers) && other.focusMove == focusMove
        override fun hashCode(): Int = keysym * 31 + modifiers.contentHashCode()
        override fun toString(): String = "Key(0x${Integer.toHexString(keysym)}, ${modifiers.joinToString { "0x" + Integer.toHexString(it) }}, focusMove=$focusMove)"
    }

    data class Text(val text: String) : KeyAction()

    /** Swallowed with nothing sent (bare modifiers, dead keys). */
    object Consumed : KeyAction() { override fun toString() = "Consumed" }

    /** Not ours: let the platform handle it. */
    object Unhandled : KeyAction() { override fun toString() = "Unhandled" }
}

/**
 * Maps hardware and IME key events to keysyms, pure (int constants of [KeyEvent] are inlined).
 * [unicodeChar] is `event.getUnicodeChar(metaState)`, [baseChar] is `event.getUnicodeChar(0)`.
 */
object AndroidKeyMap {
    fun map(keyCode: Int, metaState: Int, unicodeChar: Int, baseChar: Int): KeyAction {
        if (keyCode in MODIFIER_KEYCODES) return KeyAction.Consumed
        val ctrl = (metaState and KeyEvent.META_CTRL_ON) != 0
        val altRight = (metaState and KeyEvent.META_ALT_RIGHT_ON) != 0
        val altLeft = (metaState and KeyEvent.META_ALT_LEFT_ON) != 0 ||
            ((metaState and KeyEvent.META_ALT_ON) != 0 && !altRight)
        val shift = (metaState and KeyEvent.META_SHIFT_ON) != 0

        named(keyCode)?.let { keysym ->
            val mods = modifiers(ctrl, altLeft, shift)
            val moves = ctrl || altLeft || keysym in FOCUS_MOVING
            return KeyAction.Key(keysym, mods, moves)
        }
        if (ctrl || altLeft) {
            val base = baseKeysym(keyCode, baseChar) ?: return KeyAction.Unhandled
            return KeyAction.Key(base, modifiers(ctrl, altLeft, shift), focusMove = true)
        }
        if ((unicodeChar and COMBINING_ACCENT) != 0) return KeyAction.Consumed
        if (unicodeChar >= 0x20 && unicodeChar != 0x7f && Character.isValidCodePoint(unicodeChar)) {
            return KeyAction.Text(String(Character.toChars(unicodeChar)))
        }
        return KeyAction.Unhandled
    }

    private fun named(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_DEL -> Keysym.BACKSPACE
        KeyEvent.KEYCODE_FORWARD_DEL -> Keysym.DELETE
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Keysym.RETURN
        KeyEvent.KEYCODE_TAB -> Keysym.TAB
        KeyEvent.KEYCODE_ESCAPE -> Keysym.ESCAPE
        KeyEvent.KEYCODE_DPAD_LEFT -> Keysym.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Keysym.RIGHT
        KeyEvent.KEYCODE_DPAD_UP -> Keysym.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Keysym.DOWN
        KeyEvent.KEYCODE_MOVE_HOME -> Keysym.HOME
        KeyEvent.KEYCODE_MOVE_END -> Keysym.END
        KeyEvent.KEYCODE_PAGE_UP -> Keysym.PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> Keysym.PAGE_DOWN
        KeyEvent.KEYCODE_INSERT -> Keysym.INSERT
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> Keysym.F1 + (keyCode - KeyEvent.KEYCODE_F1)
        else -> null
    }

    /** The lowercase keysym a chord is pressed on: the unmodified character, else the key's letter/digit. */
    private fun baseKeysym(keyCode: Int, baseChar: Int): Int? {
        if (baseChar >= 0x20 && baseChar != 0x7f && Character.isValidCodePoint(baseChar)) {
            return Keysym.forCodePoint(Character.toLowerCase(baseChar))
        }
        return when (keyCode) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 'a'.code + (keyCode - KeyEvent.KEYCODE_A)
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0'.code + (keyCode - KeyEvent.KEYCODE_0)
            KeyEvent.KEYCODE_SPACE -> Keysym.SPACE
            else -> null
        }
    }

    private fun modifiers(ctrl: Boolean, alt: Boolean, shift: Boolean): IntArray {
        val count = (if (ctrl) 1 else 0) + (if (alt) 1 else 0) + (if (shift) 1 else 0)
        val out = IntArray(count)
        var i = 0
        if (ctrl) out[i++] = Keysym.CONTROL_L
        if (alt) out[i++] = Keysym.ALT_L
        if (shift) out[i] = Keysym.SHIFT_L
        return out
    }

    /** `KeyCharacterMap.COMBINING_ACCENT`: the character is a dead key. */
    private const val COMBINING_ACCENT = Int.MIN_VALUE

    private val FOCUS_MOVING = setOf(Keysym.RETURN, Keysym.TAB, Keysym.ESCAPE, Keysym.UP, Keysym.DOWN, Keysym.PAGE_UP, Keysym.PAGE_DOWN)

    private val MODIFIER_KEYCODES = setOf(
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
        KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_NUM_LOCK, KeyEvent.KEYCODE_SCROLL_LOCK,
        KeyEvent.KEYCODE_FUNCTION, KeyEvent.KEYCODE_SYM,
    )
}
