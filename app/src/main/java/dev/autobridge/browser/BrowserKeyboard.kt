package dev.autobridge.browser

/**
 * The car browser's own keyboard for the address bar and page fields: keys, layout, the text being
 * edited and where everything sits on screen. Pure, so all of it is asserted in JVM tests; the
 * Android Auto browser ([CarWebRenderer]) draws it on the car surface and the Duo Screen pane
 * ([BrowserActivity]) builds it from views ([BrowserKeyboardView]).
 *
 * ## Why not the car's keyboard
 *
 * On the template route the only keyboard the host offers is a full-screen `SearchTemplate`: the
 * page disappears behind it and it looks nothing like the browser. On the Duo pane the system IME
 * lands on whichever display it likes. Drawing our own keeps the page in view above the keys.
 *
 * The layout is tuned for addresses: a number row, QWERTY, and a bottom row of `www.`, `.com`, `/`,
 * `.`, `-` and `_` in URL mode. Search mode swaps those for punctuation a query uses. Thai stays one
 * key away because most searches here are Thai.
 */
sealed class BrowserKey {
    /** A key that types. [upper] is the shift layer's character; for Thai that is a different letter. */
    data class Text(val lower: String, val upper: String = lower) : BrowserKey()

    object Shift : BrowserKey()
    object Backspace : BrowserKey()
    object Space : BrowserKey()
    object Go : BrowserKey()
    object Language : BrowserKey()
    object CursorLeft : BrowserKey()
    object CursorRight : BrowserKey()
}

/** URL mode for the address bar; search mode for a page's own text fields. */
enum class BrowserKeyboardMode { URL, SEARCH }

/** Shift is one-shot; a second tap locks it (caps lock); a third turns it off. */
enum class ShiftState {
    OFF, ONCE, LOCKED;

    val active: Boolean get() = this != OFF

    fun next(): ShiftState = when (this) {
        OFF -> ONCE
        ONCE -> LOCKED
        LOCKED -> OFF
    }

    /** After a character is typed: a one-shot shift is spent, a lock stays. */
    fun afterType(): ShiftState = if (this == ONCE) OFF else this
}

object BrowserKeyboardLayouts {
    private val NUMBERS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
    private val LATIN = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("z", "x", "c", "v", "b", "n", "m"),
    )
    private val URL_ROW = listOf("www.", ".com", "/", ".", "-", "_")
    private val SEARCH_ROW = listOf(",", ".", "?", "-", "'", "@")

    /**
     * Every row, top to bottom; the last is the action row. Shift goes at the start of the last
     * letter row and the two cursor keys at its end, the way a phone keyboard places them.
     */
    fun rows(mode: BrowserKeyboardMode, language: CarKeyboardLanguage, shifted: Boolean): List<List<BrowserKey>> {
        val letters: List<List<BrowserKey>> = if (language == CarKeyboardLanguage.THAI) {
            val base = CarKeyboardLayouts.THAI
            val shiftedRows = CarKeyboardLayouts.THAI_SHIFTED
            (if (shifted) shiftedRows else base).map { row -> row.map { BrowserKey.Text(it) } }
        } else {
            LATIN.map { row -> row.map { BrowserKey.Text(it, it.uppercase()) } }
        }
        val numbers = NUMBERS.map { BrowserKey.Text(it) }
        val lastLetters = listOf(BrowserKey.Shift) + letters.last() + listOf(BrowserKey.CursorLeft, BrowserKey.CursorRight)
        val actions = listOf(BrowserKey.Language) +
            (if (mode == BrowserKeyboardMode.URL) URL_ROW else SEARCH_ROW).map { BrowserKey.Text(it) } +
            listOf(BrowserKey.Backspace, BrowserKey.Space, BrowserKey.Go)
        return listOf(numbers) + letters.dropLast(1) + listOf(lastLetters) + listOf(actions)
    }

    /** How much of its row a key claims, relative to a letter. */
    fun weight(key: BrowserKey): Float = when (key) {
        is BrowserKey.Space -> 2.4f
        is BrowserKey.Go -> 1.6f
        is BrowserKey.Backspace, is BrowserKey.Shift -> 1.3f
        is BrowserKey.Language -> 1.1f
        is BrowserKey.Text -> if (key.lower.length > 2) 1.3f else 1f
        else -> 1f
    }

    /** What a key types with [shift] applied; numbers and URL pieces have no shifted form. */
    fun typed(key: BrowserKey.Text, shift: ShiftState): String = if (shift.active) key.upper else key.lower
}

/**
 * The text being edited, with a selection. A collapsed selection is the cursor. Indices are UTF-16
 * offsets that never split a surrogate pair; Thai vowels and tone marks are code points of their
 * own, so backspace removes them one at a time, as every Thai keyboard does.
 */
class BrowserTextBuffer(initial: String = "", selectAll: Boolean = false) {
    var text: String = initial
        private set
    var selectionStart: Int = if (selectAll) 0 else initial.length
        private set
    var selectionEnd: Int = initial.length
        private set

    val cursor: Int get() = selectionEnd
    val hasSelection: Boolean get() = selectionStart != selectionEnd

    /** Types [value] over the selection, or at the cursor. */
    fun insert(value: String) {
        val start = minOf(selectionStart, selectionEnd)
        val end = maxOf(selectionStart, selectionEnd)
        text = text.substring(0, start) + value + text.substring(end)
        collapseTo(start + value.length)
    }

    /** Deletes the selection, or the one code point before the cursor. */
    fun backspace() {
        if (hasSelection) {
            insert("")
            return
        }
        if (cursor == 0) return
        val from = text.offsetByCodePoints(cursor, -1)
        text = text.substring(0, from) + text.substring(cursor)
        collapseTo(from)
    }

    /**
     * Moves the cursor by [codePoints]. With a selection, the first move collapses it to the side
     * it moves toward, like a desktop text field.
     */
    fun moveCursor(codePoints: Int) {
        if (hasSelection) {
            collapseTo(if (codePoints < 0) minOf(selectionStart, selectionEnd) else maxOf(selectionStart, selectionEnd))
            return
        }
        var at = cursor
        repeat(kotlin.math.abs(codePoints)) {
            at = when {
                codePoints < 0 && at > 0 -> text.offsetByCodePoints(at, -1)
                codePoints > 0 && at < text.length -> text.offsetByCodePoints(at, 1)
                else -> at
            }
        }
        collapseTo(at)
    }

    /** Puts the cursor at [index] (a tap in the field), snapped off the middle of a surrogate pair. */
    fun setCursor(index: Int) {
        var at = index.coerceIn(0, text.length)
        if (at in 1 until text.length && Character.isLowSurrogate(text[at]) && Character.isHighSurrogate(text[at - 1])) at--
        collapseTo(at)
    }

    fun selectAll() {
        selectionStart = 0
        selectionEnd = text.length
    }

    fun clear() {
        text = ""
        collapseTo(0)
    }

    private fun collapseTo(index: Int) {
        selectionStart = index
        selectionEnd = index
    }

    companion object {
        /** A buffer over [text] with the selection [start]..[end] (either order; clamped). */
        fun withSelection(text: String, start: Int, end: Int): BrowserTextBuffer = BrowserTextBuffer(text).apply {
            val a = start.coerceIn(0, text.length)
            val b = end.coerceIn(0, text.length)
            selectionStart = minOf(a, b)
            selectionEnd = maxOf(a, b)
        }
    }
}

/**
 * Where the keyboard's parts sit inside [area] (the part of the car surface the browser owns), in
 * surface pixels. [density] is pixels per dp of that surface.
 *
 * Sizing rule: the tray takes at most ~60% of the height, so the page stays readable above it,
 * with keys up to 56dp tall. On a short screen where that would make keys smaller than 36dp it
 * may grow to ~82% instead: a key a driver can hit matters more than seeing more page. Letter keys
 * share one width across rows, centred, like a phone keyboard; the action row fills the width.
 * On a very wide screen the keys stay within [MAX_CONTENT_DP] and centre.
 */
data class BrowserKeyboardGeometry(
    val tray: Box,
    val field: Box,
    val clear: Box,
    val hide: Box,
    val keys: List<Pair<BrowserKey, Box>>,
    val keyHeight: Float,
) {
    fun keyAt(x: Float, y: Float): BrowserKey? = keys.firstOrNull { it.second.contains(x, y) }?.first

    companion object {
        const val MAX_CONTENT_DP = 1100f
        const val MAX_KEY_DP = 56f
        const val MIN_COMFORT_KEY_DP = 36f
        const val NORMAL_SHARE = 0.6f
        const val TIGHT_SHARE = 0.82f

        fun create(area: Box, density: Float, rows: List<List<BrowserKey>>): BrowserKeyboardGeometry {
            fun dp(value: Float) = value * density
            val pad = dp(8f)
            val gap = dp(5f)
            val lines = rows.size + 1
            val fixed = pad * 2 + gap * (lines - 1)
            var keyH = ((area.height * NORMAL_SHARE - fixed) / lines).coerceAtMost(dp(MAX_KEY_DP))
            if (keyH < dp(MIN_COMFORT_KEY_DP)) {
                keyH = ((area.height * TIGHT_SHARE - fixed) / lines).coerceAtMost(dp(MIN_COMFORT_KEY_DP))
            }
            keyH = keyH.coerceAtLeast(1f)
            val trayH = fixed + keyH * lines
            val tray = Box(area.left, area.bottom - trayH, area.right, area.bottom)
            val contentW = minOf(area.width - pad * 2, dp(MAX_CONTENT_DP)).coerceAtLeast(1f)
            val contentLeft = area.centerX - contentW / 2f
            val contentRight = contentLeft + contentW

            var top = tray.top + pad
            val hide = Box(contentRight - keyH, top, contentRight, top + keyH)
            val field = Box(contentLeft, top, hide.left - gap, top + keyH)
            val clear = Box(field.right - keyH, top, field.right, top + keyH)
            top += keyH + gap

            // One unit for every letter row, set by whichever row is widest in weight.
            val letterRows = rows.dropLast(1)
            val unit = letterRows.minOf { row ->
                (contentW - gap * (row.size - 1)) / row.sumOf { BrowserKeyboardLayouts.weight(it).toDouble() }.toFloat()
            }
            val keys = ArrayList<Pair<BrowserKey, Box>>()
            letterRows.forEach { row ->
                val rowW = row.sumOf { BrowserKeyboardLayouts.weight(it).toDouble() }.toFloat() * unit + gap * (row.size - 1)
                var x = contentLeft + (contentW - rowW) / 2f
                row.forEach { key ->
                    val w = BrowserKeyboardLayouts.weight(key) * unit
                    keys += key to Box(x, top, x + w, top + keyH)
                    x += w + gap
                }
                top += keyH + gap
            }
            val actions = rows.last()
            val actionUnit = (contentW - gap * (actions.size - 1)) /
                actions.sumOf { BrowserKeyboardLayouts.weight(it).toDouble() }.toFloat()
            var x = contentLeft
            actions.forEach { key ->
                val w = BrowserKeyboardLayouts.weight(key) * actionUnit
                keys += key to Box(x, top, x + w, top + keyH)
                x += w + gap
            }
            return BrowserKeyboardGeometry(tray, field, clear, hide, keys, keyH)
        }
    }
}
