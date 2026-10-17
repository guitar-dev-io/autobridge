package dev.autobridge.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Radio search matching: names case-insensitively (Thai too) and frequencies however they are typed. */
class RadioStationMatcherTest {
    private fun assertMatches(name: String, query: String) =
        assertTrue("\"$query\" should match \"$name\"", RadioStationMatcher.matches(name, query))

    private fun assertNoMatch(name: String, query: String) =
        assertFalse("\"$query\" should not match \"$name\"", RadioStationMatcher.matches(name, query))

    @Test fun `latin names match case-insensitively`() {
        assertMatches("Cool Fahrenheit 93", "COOL")
        assertMatches("COOL FAHRENHEIT 93", "fahrenheit")
    }

    @Test fun `thai name substring matches`() {
        assertMatches("ลูกทุ่ง FM 95", "ลูกทุ่ง")
        assertMatches("สถานีวิทยุลูกทุ่ง", "ลูกทุ่ง")
    }

    @Test fun `thai sara am composed and decomposed match each other`() {
        val composed = "\u0E19\u0E49\u0E33" // น้ำ with SARA AM U+0E33
        val decomposed = "\u0E19\u0E49\u0E4D\u0E32" // น้ + NIKHAHIT + SARA AA
        assertMatches("$composed FM", decomposed)
        assertMatches("$decomposed FM", composed)
    }

    @Test fun `thai digits match ascii frequencies`() {
        assertMatches("95.5 Delight", "๙๕.๕")
    }

    @Test fun `a whole number matches the integer part but not inside a longer number`() {
        assertMatches("95.5 Delight", "95")
        assertMatches("Radio 95FM", "95")
        assertNoMatch("1095 Gold", "95")
    }

    @Test fun `frequencies match however they are typed`() {
        listOf("95.5", "FM 95.5", "fm95.5", "95,5", "955", "95.5 MHz", "95.5fm", "  95 . 5", "95  .5")
            .forEach { assertMatches("95.5 Delight", it) }
        assertMatches("101.5 FM", "1015")
        assertMatches("Cool 93", "93.0")
        assertNoMatch("95.5 Delight", "95.6")
    }

    @Test fun `multi-word queries need every word, not one substring`() {
        assertMatches("Cool Fahrenheit 93", "cool 93")
        assertMatches("Cool93", "cool 93")
        assertNoMatch("Cool Fahrenheit 93", "cool 97")
    }

    @Test fun `digits glued to letters are numbers`() {
        assertMatches("Cool93", "93")
        assertMatches("97qfm", "97")
    }

    @Test fun `band word alone still searches`() {
        assertMatches("FM 99 Active", "fm")
        assertEquals(listOf("fm"), RadioStationMatcher.queryTokens("FM"))
    }

    @Test fun `blank queries match nothing`() {
        assertNoMatch("95.5 Delight", "")
        assertNoMatch("95.5 Delight", "   ")
        assertTrue(RadioStationMatcher.rank(listOf("a"), " ") { it }.isEmpty())
    }

    @Test fun `normalize collapses spaces and decimal separators`() {
        assertEquals("fm 95.5 delight", RadioStationMatcher.normalize("  FM  95 , 5 -- Delight "))
        assertEquals("95.5", RadioStationMatcher.normalize("๙๕.๕"))
    }

    @Test fun `rank puts exact before prefix before others and keeps order within a tier`() {
        val names = listOf("Radio Cool 2", "Cool 93 B", "Cool", "Radio Cool 1", "Cool 93 A")
        assertEquals(
            listOf("Cool", "Cool 93 B", "Cool 93 A", "Radio Cool 2", "Radio Cool 1"),
            RadioStationMatcher.rank(names, "cool") { it }
        )
    }

    @Test fun `api query drops band words`() {
        assertEquals("95.5", RadioStationMatcher.apiQuery("FM 95.5"))
        assertEquals("95", RadioStationMatcher.apiQuery("คลื่น 95"))
        assertEquals("95", RadioStationMatcher.apiQuery("คลื่น95"))
        assertEquals("cool 93", RadioStationMatcher.apiQuery("Cool  93"))
    }
}
