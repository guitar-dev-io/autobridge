package dev.autobridge.desktop.keyboard

/**
 * Chrome has no preedit channel here, so composing text is typed for real and corrected by
 * diff: BackSpace over what differs after the common prefix (in code points), then type the
 * new tail. One BackSpace per code point, the unit Chrome deletes Thai combining marks in.
 */
object CompositionDiff {
    data class Diff(val backspaces: Int, val insert: String) {
        val isEmpty: Boolean get() = backspaces == 0 && insert.isEmpty()
    }

    fun diff(old: String, new: String): Diff {
        var i = 0
        var j = 0
        while (i < old.length && j < new.length) {
            val a = old.codePointAt(i)
            val b = new.codePointAt(j)
            if (a != b) break
            i += Character.charCount(a)
            j += Character.charCount(b)
        }
        return Diff(old.codePointCount(i, old.length), new.substring(j))
    }

    fun codePoints(text: String): Int = text.codePointCount(0, text.length)

    /** How a commit reaches Chrome. */
    sealed class Commit {
        /** Ends a composition: correct the typed composing text by [diff]; never pasted. */
        data class Retype(val diff: Diff) : Commit()
        data class Paste(val text: String) : Commit()
        data class Type(val text: String) : Commit()
    }

    /**
     * The single commit rule: a commit that ends a composition is diffed against it (pasting
     * would duplicate the already-typed composing text); otherwise text longer than
     * [pasteThreshold] code points, or multi-line, is pasted, and the rest typed.
     */
    fun commit(composing: String, text: String, pasteThreshold: Int): Commit {
        if (composing.isNotEmpty()) return Commit.Retype(diff(composing, text))
        val length = codePoints(text)
        return if (length > pasteThreshold || (length > 1 && text.indexOf('\n') >= 0)) Commit.Paste(text) else Commit.Type(text)
    }
}
