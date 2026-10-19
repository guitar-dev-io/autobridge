package dev.autobridge.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression-pins [CarJsEscape.singleQuoted], the only place the CarWebRenderer route turns the raw
 * keyboard buffer into a JS string. The escaper is already safe for the hostile inputs below; this
 * test fixes its output so a later edit cannot silently weaken it — in particular it cannot collapse
 * the two distinct line/paragraph separator arms (U+2028 / U+2029) into one, which would reopen a
 * string-break injection.
 */
class CarWebRendererJsEscapeTest {

    @Test
    fun `backslash is doubled`() {
        assertEquals("\\\\", CarJsEscape.singleQuoted("\\"))
    }

    @Test
    fun `single quote is escaped so the literal cannot be closed`() {
        assertEquals("\\'", CarJsEscape.singleQuoted("'"))
    }

    @Test
    fun `double quote is escaped`() {
        assertEquals("\\\"", CarJsEscape.singleQuoted("\""))
    }

    @Test
    fun `newline and carriage return are escaped`() {
        assertEquals("\\n", CarJsEscape.singleQuoted("\n"))
        assertEquals("\\r", CarJsEscape.singleQuoted("\r"))
    }

    @Test
    fun `line separator and paragraph separator are distinct arms`() {
        // U+2028 and U+2029 are JS string line terminators; both must be escaped, separately.
        assertEquals("\\u2028", CarJsEscape.singleQuoted("\u2028"))
        assertEquals("\\u2029", CarJsEscape.singleQuoted("\u2029"))
    }

    @Test
    fun `less-than is escaped so a script tag cannot be formed`() {
        assertEquals("\\x3C", CarJsEscape.singleQuoted("<"))
    }

    @Test
    fun `a full injection attempt is neutralised`() {
        val hostile = "</script>');alert(1);//"
        val escaped = CarJsEscape.singleQuoted(hostile)
        // No raw single quote, no raw '<' survives to break out of the literal or open a tag.
        assertTrue("raw quote leaked: $escaped", !escaped.contains("'") || escaped.contains("\\'"))
        assertTrue("raw < leaked: $escaped", !escaped.contains("<"))
        assertEquals(
            "\\x3C/script>\\');alert(1);//",
            escaped
        )
    }

    @Test
    fun `ordinary characters pass through unchanged`() {
        assertEquals("netflix เพลง", CarJsEscape.singleQuoted("netflix เพลง"))
    }
}
