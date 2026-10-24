package dev.autobridge.desktop.keyboard

import dev.autobridge.desktop.keyboard.CompositionDiff.Commit
import dev.autobridge.desktop.keyboard.CompositionDiff.Diff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionDiffTest {
    /**
     * Chrome's field as the bridge drives it: composing text is typed for real and corrected by
     * diff, commits follow [CompositionDiff.commit]. Counts every key sent.
     */
    private class Field(initial: String = "") {
        val cps = initial.codePoints().toArray().toMutableList()
        var composing = ""
        var keys = 0
        var pasted = 0

        val text: String get() = String(cps.toIntArray(), 0, cps.size)

        fun apply(d: Diff) {
            repeat(d.backspaces) { cps.removeAt(cps.size - 1) }
            d.insert.codePoints().forEach { cps += it }
            keys += d.backspaces + CompositionDiff.codePoints(d.insert)
        }

        fun compose(t: String) {
            apply(CompositionDiff.diff(composing, t))
            composing = t
        }

        fun commit(t: String) {
            when (val c = CompositionDiff.commit(composing, t, 32)) {
                is Commit.Retype -> { apply(c.diff); composing = "" }
                is Commit.Paste -> { c.text.codePoints().forEach { cps += it }; pasted++ }
                is Commit.Type -> { c.text.codePoints().forEach { cps += it }; keys += CompositionDiff.codePoints(c.text) }
            }
        }
    }

    @Test fun latinGrowAndShrink() {
        assertEquals(Diff(0, "p"), CompositionDiff.diff("hel", "help"))
        assertEquals(Diff(2, ""), CompositionDiff.diff("help", "he"))
        assertEquals(Diff(0, ""), CompositionDiff.diff("same", "same"))
        assertTrue(CompositionDiff.diff("same", "same").isEmpty)
    }

    @Test fun composingThenCommitGivesTheWordOnce() {
        val f = Field()
        for (t in listOf("h", "he", "hel", "help")) f.compose(t)
        f.commit("help")
        assertEquals("help", f.text)
        assertEquals(4, f.keys)
        assertEquals("", f.composing)
    }

    @Test fun thaiCombiningMarks() {
        assertEquals(Diff(0, "นี่"), CompositionDiff.diff("ที่", "ที่นี่"))
        // Changing the tone mark deletes exactly the mark (one code point).
        assertEquals(Diff(1, "้"), CompositionDiff.diff("ที่", "ที้"))
        val f = Field()
        f.compose("ที่")
        f.compose("ที่นี่")
        f.commit("ที่นี่")
        assertEquals("ที่นี่", f.text)
    }

    @Test fun surrogatePairsCountAsOneCodePoint() {
        assertEquals(Diff(1, "🙂"), CompositionDiff.diff("a😀", "a🙂"))
        assertEquals(Diff(2, ""), CompositionDiff.diff("😀😀", ""))
        assertEquals(2, CompositionDiff.codePoints("😀😀"))
    }

    @Test fun replaceFromTheMiddle() {
        assertEquals(Diff(3, "Xde"), CompositionDiff.diff("abcde", "abXde"))
    }

    @Test fun toEmpty() {
        assertEquals(Diff(5, ""), CompositionDiff.diff("hello", ""))
        assertEquals(Diff(0, "hi"), CompositionDiff.diff("", "hi"))
    }

    @Test fun composingFortyCodePointsThenIdenticalCommitSendsNothing() {
        val word = "ก".repeat(20) + "a".repeat(20)
        assertEquals(40, CompositionDiff.codePoints(word))
        val f = Field()
        f.compose(word)
        val before = f.keys
        f.commit(word)
        assertEquals(before, f.keys)
        assertEquals(0, f.pasted)
        assertEquals(word, f.text)
        assertEquals(Commit.Retype(Diff(0, "")), CompositionDiff.commit(word, word, 32))
    }

    @Test fun composingThenLongDifferentCommitIsDiffedNotPasted() {
        val long = "x".repeat(50)
        val f = Field()
        f.compose("abc")
        f.commit(long)
        assertEquals(long, f.text)
        assertEquals(0, f.pasted)
        assertEquals(3 + 3 + 50, f.keys)
    }

    @Test fun nonComposingCommitRule() {
        assertEquals(Commit.Type("short"), CompositionDiff.commit("", "short", 32))
        assertEquals(Commit.Type("x".repeat(32)), CompositionDiff.commit("", "x".repeat(32), 32))
        assertEquals(Commit.Paste("x".repeat(33)), CompositionDiff.commit("", "x".repeat(33), 32))
        assertEquals(Commit.Paste("a\nb"), CompositionDiff.commit("", "a\nb", 32))
        assertEquals(Commit.Type("\n"), CompositionDiff.commit("", "\n", 32))
    }
}
