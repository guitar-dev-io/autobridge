package dev.autobridge.desktop.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TextSanitizerTest {
    @Test fun cleanTextIsReturnedAsIs() {
        val text = "hello ที่นี่ 😀\tok\n"
        assertEquals(text, TextSanitizer.sanitize(text))
        val plain = "plain ascii ไทย"
        assertSame(plain, TextSanitizer.sanitize(plain))
    }

    @Test fun unpairedSurrogatesBecomeReplacementCharacter() {
        assertEquals("a\uFFFDb", TextSanitizer.sanitize("a\uD83Db"))
        assertEquals("a\uFFFDb", TextSanitizer.sanitize("a\uDE00b"))
        assertEquals("\uFFFD", TextSanitizer.sanitize("\uD83D"))
        assertEquals("\uFFFD\uFFFD", TextSanitizer.sanitize("\uDE00\uD83D"))
    }

    @Test fun surrogatePairsAreKept() {
        assertEquals("x😀y\u0000".dropLast(1), TextSanitizer.sanitize("x😀y\u0000"))
    }

    @Test fun lineEndingsAreNormalized() {
        assertEquals("a\nb\nc\n\n", TextSanitizer.sanitize("a\r\nb\rc\r\r"))
        assertEquals("a\n\nb", TextSanitizer.sanitize("a\n\r\nb"))
    }

    @Test fun controlsOtherThanNewlineAndTabAreDropped() {
        assertEquals("ab\tc\nd", TextSanitizer.sanitize("a\u0000b\t\u0007c\n\u001bd\u007f"))
    }

    @Test fun emptyStaysEmpty() {
        assertEquals("", TextSanitizer.sanitize(""))
    }
}
