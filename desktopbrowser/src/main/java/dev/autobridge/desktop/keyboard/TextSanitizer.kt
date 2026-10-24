package dev.autobridge.desktop.keyboard

/**
 * Cleans text before it is typed or pasted into the guest: unpaired surrogates become U+FFFD,
 * `\r\n` and `\r` become `\n`, and C0 controls other than `\n`/`\t` (and DEL) are dropped.
 */
object TextSanitizer {
    private const val REPLACEMENT = '\uFFFD'

    fun sanitize(text: String): String {
        if (text.none { it.isSurrogate() || it < ' ' || it == '\u007f' }) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                    out.append(c).append(text[i + 1]); i++
                }
                c.isSurrogate() -> out.append(REPLACEMENT)
                c == '\r' -> {
                    out.append('\n')
                    if (i + 1 < text.length && text[i + 1] == '\n') i++
                }
                c == '\n' || c == '\t' -> out.append(c)
                c < ' ' || c == '\u007f' -> Unit
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }
}
