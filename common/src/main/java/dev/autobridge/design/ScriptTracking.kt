package dev.autobridge.design

/**
 * Letter spacing that only applies to scripts it suits.
 *
 * The small uppercase section labels are tracked out (0.14 em) because spaced capitals read well
 * in Latin. Thai has no capitals, and spacing pulls its vowels and tone marks away from the
 * consonant they sit on, so "ธีม" shows up as "ธี ม". The same goes for the other scripts that
 * stack or join marks (Lao, Khmer, Myanmar, Arabic): text in any of them gets no tracking.
 */
object ScriptTracking {

    /** [latinEm] when [text] is free of untrackable scripts, else 0. Compose callers use [allowsTracking]. */
    fun letterSpacing(text: CharSequence, latinEm: Float): Float = if (allowsTracking(text)) latinEm else 0f

    fun allowsTracking(text: CharSequence): Boolean = text.none(::isUntrackable)

    private fun isUntrackable(c: Char): Boolean =
        c in '\u0E00'..'\u0EFF' || // Thai, Lao
            c in '\u1780'..'\u17FF' || // Khmer
            c in '\u1000'..'\u109F' || // Myanmar
            c in '\u0600'..'\u06FF' // Arabic
}
