package dev.autobridge.browser

import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDefaultsTest {
    @Test fun bothBrowsersKeepHttpsPathsAndQueries() {
        assertEquals("https://example.com/watch?v=123", BrowserDefaults.resolve(" https://example.com/watch?v=123 "))
        assertEquals("https://example.com", BrowserDefaults.resolve("example.com"))
    }

    @Test fun thaiSearchAndSpecialCharactersRoundTripAsOneQuery() {
        val query = "เพลงไทย & jazz + live"
        val url = BrowserDefaults.resolve(query)
        assertTrue(url.startsWith("https://www.google.com/search?q="))
        assertEquals(query, URLDecoder.decode(url.substringAfter("?q="), "UTF-8"))
    }

    @Test fun unsafeSchemesBecomeSearchesRatherThanNavigation() {
        for (input in listOf("http://example.com", "javascript:alert(1)", "intent://example.com")) {
            assertTrue(BrowserDefaults.resolve(input).startsWith("https://www.google.com/search?q="))
        }
    }
}
