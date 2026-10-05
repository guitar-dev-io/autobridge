package dev.autobridge.browser

import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserInputResolverTest {
    private fun resolve(input: String, engine: SearchEngine) =
        BrowserInputResolver.resolveBrowserInput(input, engine)

    @Test fun aFullUrlIsSentExactlyAsTypedWhicheverEngineIsSelected() {
        val url = "https://m.youtube.com/watch?v=123"
        assertEquals(url, resolve(url, SearchEngine.YOUTUBE))
        assertEquals(url, resolve(url, SearchEngine.GOOGLE))
        // Surrounding whitespace is trimmed, the path and query survive.
        assertEquals(url, resolve("  $url  ", SearchEngine.YOUTUBE))
    }

    @Test fun aBareHostnameIsUpgradedToHttps() {
        assertEquals("https://youtube.com", resolve("youtube.com", SearchEngine.YOUTUBE))
        assertEquals("https://example.com", resolve("example.com", SearchEngine.GOOGLE))
    }

    @Test fun aFreeTextQueryBecomesAYouTubeSearch() {
        val url = resolve("bodyslam live", SearchEngine.YOUTUBE)
        assertTrue(url.startsWith("https://m.youtube.com/results?search_query="))
        assertEquals("bodyslam live", URLDecoder.decode(url.substringAfter("="), "UTF-8"))
    }

    @Test fun aFreeTextQueryBecomesAGoogleSearch() {
        val url = resolve("bodyslam live", SearchEngine.GOOGLE)
        assertTrue(url.startsWith("https://www.google.com/search?q="))
        assertEquals("bodyslam live", URLDecoder.decode(url.substringAfter("q="), "UTF-8"))
    }

    @Test fun thaiAndSpecialCharactersRoundTripThroughTheQuery() {
        val query = "เพลง bodyslam ล่าสุด & live"
        val yt = resolve(query, SearchEngine.YOUTUBE)
        assertEquals(query, URLDecoder.decode(yt.substringAfter("search_query="), "UTF-8"))
        val g = resolve(query, SearchEngine.GOOGLE)
        assertEquals(query, URLDecoder.decode(g.substringAfter("q="), "UTF-8"))
    }

    @Test fun unsafeSchemesAreTreatedAsSearchesNotNavigation() {
        for (input in listOf("javascript:alert(1)", "intent://x", "http://insecure.example")) {
            assertTrue(
                "$input should become a search",
                resolve(input, SearchEngine.GOOGLE).startsWith("https://www.google.com/search?q=")
            )
        }
    }

    @Test fun emptyOrBlankInputBecomesAnEmptySearchNeverAUrl() {
        // The projection address bar passes whatever onSearchSubmitted delivers straight through,
        // so empty/blank must resolve to a (safe) search URL and never crash or navigate off-scheme.
        for (engine in SearchEngine.entries) {
            assertEquals(engine.searchUrl(""), resolve("", engine))
            assertEquals(engine.searchUrl(""), resolve("   ", engine))
        }
        // And the result is a real https search URL, not a bare host or empty string.
        assertTrue(resolve("", SearchEngine.GOOGLE).startsWith("https://www.google.com/search?q="))
        assertTrue(resolve("   ", SearchEngine.YOUTUBE).startsWith("https://m.youtube.com/results?search_query="))
    }

    @Test fun projectionBarContractBareDomainFullUrlAndMultiWordSearch() {
        // The projection browser's address bar contract, asserted explicitly: bare domain -> https,
        // full https URL preserved, multi-word phrase -> engine search.
        assertEquals("https://youtube.com", resolve("youtube.com", SearchEngine.YOUTUBE))
        assertEquals(
            "https://m.youtube.com/watch?v=abc",
            resolve("https://m.youtube.com/watch?v=abc", SearchEngine.YOUTUBE),
        )
        val search = resolve("thai pop live concert", SearchEngine.GOOGLE)
        assertTrue(search.startsWith("https://www.google.com/search?q="))
        assertEquals("thai pop live concert", URLDecoder.decode(search.substringAfter("q="), "UTF-8"))
    }

    @Test fun looksLikeUrlDistinguishesAddressesFromQueries() {
        assertTrue(BrowserInputResolver.looksLikeUrl("https://m.youtube.com/watch?v=1"))
        assertTrue(BrowserInputResolver.looksLikeUrl("youtube.com"))
        assertFalse(BrowserInputResolver.looksLikeUrl("bodyslam live"))
        assertFalse(BrowserInputResolver.looksLikeUrl("javascript:alert(1)"))
    }
}
