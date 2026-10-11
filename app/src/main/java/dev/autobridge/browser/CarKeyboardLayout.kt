package dev.autobridge.browser

import android.content.Context
import androidx.core.content.edit

/**
 * The on-screen keyboard's key map, kept pure so the layouts can be asserted in a JVM test.
 *
 * ## Why the app draws a keyboard at all
 *
 * Android attaches no IME to the projected car display. A `<input>` inside the page can be focused
 * and still raise nothing, which is what "tapped the Google box, no keyboard" is. Only a surface
 * the host draws can take text, and on the projection route the host offers exactly one —
 * `SearchController`'s search box — which not every host (the Desktop Head Unit among them) backs
 * with a keyboard. The template route has no such problem because `SearchTemplate` makes the host
 * draw one; see [dev.autobridge.car.CarBrowserSearchScreen].
 *
 * That leaves drawing our own. The projection route can, because unlike a template it owns its
 * whole view tree, so a key grid is just more views over the page.
 *
 * ## Shape
 *
 * Layout data only: no view, no context, no state beyond what a caller passes in. The keys say
 * what they mean ([CarKey]), and what each one *does* belongs to the surface that drew it.
 */
sealed class CarKey {
    /**
     * A key that types. [upper] is what the shift layer produces; on Thai that is a different
     * character entirely rather than a case change, which is why both are carried explicitly
     * instead of being derived with `uppercase()`.
     */
    data class Text(val lower: String, val upper: String = lower) : CarKey()

    object Shift : CarKey()
    object Backspace : CarKey()
    object Space : CarKey()

    /** Commits what has been typed. */
    object Go : CarKey()

    /** Switches between [CarKeyboardLanguage]s. */
    object Language : CarKey()

    /** Switches the letter rows for the punctuation layer and back. */
    object Symbols : CarKey()
}

enum class CarKeyboardLanguage { THAI, LATIN }

object CarKeyboardLayouts {

    /**
     * The rows to draw, including the function row at the bottom.
     *
     * Rows are not padded to equal length: each row's keys share that row's width, which is how a
     * phone keyboard already looks and what keeps a 9-key row's targets comfortably large on a
     * narrow head unit.
     */
    fun rows(
        language: CarKeyboardLanguage,
        shifted: Boolean,
        symbols: Boolean,
    ): List<List<CarKey>> {
        val letters = when {
            symbols -> SYMBOLS
            language == CarKeyboardLanguage.THAI -> if (shifted) THAI_SHIFTED else THAI
            else -> LATIN
        }
        return letters.map { row -> row.map { CarKey.Text(it, latinUpper(it, language, symbols)) } } +
            listOf(functionRow(language, symbols))
    }

    /**
     * Latin shift is a case change, so it is derived; Thai and the symbol layer carry their own
     * shifted characters in a separate table and must not be case-mapped.
     */
    private fun latinUpper(key: String, language: CarKeyboardLanguage, symbols: Boolean): String =
        if (symbols || language == CarKeyboardLanguage.THAI) key else key.uppercase()

    private fun functionRow(language: CarKeyboardLanguage, symbols: Boolean): List<CarKey> =
        listOf(
            CarKey.Shift,
            CarKey.Language,
            CarKey.Symbols,
            CarKey.Space,
            CarKey.Text(if (language == CarKeyboardLanguage.THAI && !symbols) "ฯ" else "."),
            CarKey.Backspace,
            CarKey.Go,
        )

    /** How much of a row's width a key claims. Space earns its size; everything else is even. */
    fun weight(key: CarKey): Float = when (key) {
        is CarKey.Space -> 3f
        is CarKey.Go -> 1.6f
        is CarKey.Backspace -> 1.4f
        is CarKey.Shift, is CarKey.Language, is CarKey.Symbols -> 1.3f
        else -> 1f
    }

    // Kedmanee, the layout every Thai keyboard uses, minus the rows a car has no room for.
    internal val THAI = listOf(
        listOf("ๆ", "ไ", "ำ", "พ", "ะ", "ั", "ี", "ร", "น", "ย", "บ", "ล"),
        listOf("ฟ", "ห", "ก", "ด", "เ", "้", "่", "า", "ส", "ว", "ง"),
        listOf("ผ", "ป", "แ", "อ", "ิ", "ื", "ท", "ม", "ใ", "ฝ"),
        listOf("ภ", "ถ", "ุ", "ึ", "ค", "ต", "จ", "ข", "ช"),
    )

    internal val THAI_SHIFTED = listOf(
        listOf("๐", "ฎ", "ฑ", "ธ", "ํ", "๊", "ณ", "ฯ", "ญ", "ฐ"),
        listOf("ฤ", "ฆ", "ฏ", "โ", "ฌ", "็", "๋", "ษ", "ศ", "ซ"),
        listOf("ฉ", "ฮ", "ฺ", "์", "?", "ฒ", "ฬ", "ฦ"),
        listOf("๑", "๒", "๓", "๔", "ู", "฿", "๕", "๖", "๗", "๘", "๙"),
    )

    private val LATIN = listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("z", "x", "c", "v", "b", "n", "m"),
    )

    private val SYMBOLS = listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        listOf("@", "#", "%", "&", "-", "+", "(", ")", "/", "*"),
        listOf(":", ";", "!", "?", "_", "=", "\"", "'", "~"),
        listOf(".", ",", "<", ">", "[", "]", "{", "}", "|"),
    )
}

/**
 * Which language the car keyboard opens in. Remembered because a driver who types Thai types Thai
 * every time, and re-picking it on every search is a tap they should not have to spend.
 *
 * Stored in the same `autobridge_browser` file the rest of the browser reads.
 */
object CarKeyboardStore {
    private const val PREFS_NAME = "autobridge_browser"
    private const val KEY_LANGUAGE = "car_keyboard_language"

    fun language(context: Context): CarKeyboardLanguage =
        runCatching {
            CarKeyboardLanguage.valueOf(
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_LANGUAGE, CarKeyboardLanguage.THAI.name).orEmpty()
            )
        }.getOrDefault(CarKeyboardLanguage.THAI)

    fun setLanguage(context: Context, language: CarKeyboardLanguage) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_LANGUAGE, language.name) }
    }
}
