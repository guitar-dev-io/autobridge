package dev.autobridge.desktop.input

import dev.autobridge.desktop.display.X11Connection

/** X keysyms for the keys the bridge sends by name. */
object Keysym {
    const val BACKSPACE = 0xff08
    const val TAB = 0xff09
    const val RETURN = 0xff0d
    const val ESCAPE = 0xff1b
    const val DELETE = 0xffff
    const val HOME = 0xff50
    const val LEFT = 0xff51
    const val UP = 0xff52
    const val RIGHT = 0xff53
    const val DOWN = 0xff54
    const val PAGE_UP = 0xff55
    const val PAGE_DOWN = 0xff56
    const val END = 0xff57
    const val F5 = 0xffc2
    const val F11 = 0xffc8
    const val SHIFT_L = 0xffe1
    const val CONTROL_L = 0xffe3
    const val ALT_L = 0xffe9
    const val SPACE = 0x20

    /** The keysym a character types: Latin-1 maps to itself, anything else to 0x01000000 + code point. */
    fun forCodePoint(codePoint: Int): Int = when {
        codePoint == '\n'.code -> RETURN
        codePoint == '\t'.code -> TAB
        codePoint in 0x20..0x7e || codePoint in 0xa0..0xff -> codePoint
        else -> 0x01000000 or codePoint
    }
}

/**
 * Types characters and key chords through XTEST. A keysym already on the keyboard map is pressed
 * on its keycode (with Shift for the second column); one that is not (Thai, emoji) is bound to a
 * spare keycode first. Spare keycodes are reused round-robin, so typing never runs out of them.
 */
class X11Keyboard(private val x: X11Connection) {
    private var mapping: Array<IntArray> = x.keyboardMapping()
    private val spare: List<Int> = mapping.indices
        .filter { i -> mapping[i].all { it == 0 } }
        .map { it + x.setup.minKeycode }
        .takeLast(8)
    private var nextSpare = 0
    private val remapped = HashMap<Int, Int>()

    fun typeText(text: String) {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            tapKeysym(Keysym.forCodePoint(cp))
            i += Character.charCount(cp)
        }
    }

    fun tapKeysym(keysym: Int, vararg modifiers: Int) {
        val (keycode, shifted) = keycodeFor(keysym) ?: return
        val mods = modifiers.toList().mapNotNull { keycodeFor(it)?.first }.toMutableList()
        if (shifted) keycodeFor(Keysym.SHIFT_L)?.first?.let { mods += it }
        mods.forEach { x.fakeInput(X11Connection.KEY_PRESS, it) }
        x.fakeInput(X11Connection.KEY_PRESS, keycode)
        x.fakeInput(X11Connection.KEY_RELEASE, keycode)
        mods.asReversed().forEach { x.fakeInput(X11Connection.KEY_RELEASE, it) }
    }

    private fun keycodeFor(keysym: Int): Pair<Int, Boolean>? {
        remapped[keysym]?.let { return it to false }
        for (i in mapping.indices) {
            val row = mapping[i]
            if (row.isNotEmpty() && row[0] == keysym) return (i + x.setup.minKeycode) to false
            if (row.size > 1 && row[1] == keysym) return (i + x.setup.minKeycode) to true
        }
        if (spare.isEmpty()) return null
        val keycode = spare[nextSpare++ % spare.size]
        remapped.entries.removeAll { it.value == keycode }
        val columns = mapping.firstOrNull()?.size ?: 2
        x.changeKeyboardMapping(keycode, IntArray(columns) { if (it < 2) keysym else 0 })
        x.sync()
        mapping[keycode - x.setup.minKeycode] = IntArray(columns) { if (it < 2) keysym else 0 }
        remapped[keysym] = keycode
        return keycode to false
    }
}
