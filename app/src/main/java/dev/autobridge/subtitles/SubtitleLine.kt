package dev.autobridge.subtitles

/**
 * One subtitle line on its way to the screen, with whatever is known about it so far.
 *
 * [translated] is null until an engine has answered, which is the normal state for the first few
 * hundred milliseconds of every line: the original is put on screen immediately and replaced in
 * place when the translation arrives, because a subtitle that appears late has already missed the
 * dialogue it belongs to.
 */
data class SubtitleLine(
    val original: String,
    val translated: String? = null,
    val showOriginal: Boolean = false,
    /** Set when an engine was asked and failed, so the screen can explain itself once. */
    val error: String? = null
) {
    val isBlank: Boolean get() = original.isBlank() && translated.isNullOrBlank()

    /**
     * What the player draws. The translation leads and the original follows underneath, because
     * someone reading the translation should not have to look past the line they cannot read.
     */
    val displayText: String
        get() {
            val target = translated?.takeIf { it.isNotBlank() } ?: return original
            if (!showOriginal || target == original) return target
            return target + "\n" + original
        }

    companion object {
        val NONE = SubtitleLine(original = "")
    }
}

/**
 * Cue text as it comes out of a subtitle track, reduced to the sentence an engine can work with.
 *
 * A cue is not a sentence: WebVTT and SubRip carry markup, line breaks inserted to fit a TV
 * screen, and position/voice tags. Feeding those to a translator costs quality twice - the markup
 * becomes words, and a sentence broken over two display lines is translated as two fragments - so
 * the breaks are joined and the tags removed before anything is queued.
 */
object SubtitleCueText {
    private val tag = Regex("<[^>]*>")
    private val brace = Regex("\\{[^}]*\\}")
    private val whitespace = Regex("\\s+")
    private const val NO_BREAK_SPACE = ' '

    /** The cue reduced to one line of plain text, or an empty string when nothing is left. */
    fun normalize(raw: CharSequence?): String {
        val text = raw?.toString() ?: return ""
        return text
            .replace(tag, " ")
            // Aegisub/SSA override blocks, which some IPTV tracks carry verbatim.
            .replace(brace, " ")
            .replace(NO_BREAK_SPACE, ' ')
            .replace(whitespace, " ")
            .trim()
    }

    /** Several cues shown at once, joined in the order the track gave them. */
    fun normalizeAll(raw: List<CharSequence?>): String =
        raw.map { normalize(it) }.filter { it.isNotEmpty() }.joinToString(" ")

    /**
     * True when a line is not worth translating: nothing but punctuation, music notes or the
     * speaker dashes a caption track uses. Sending these to a Marian decoder produces invented
     * sentences, because the model has never seen an input that says nothing.
     */
    fun isUntranslatable(text: String): Boolean =
        text.none { it.isLetter() || it.isDigit() }
}
