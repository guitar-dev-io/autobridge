package dev.autobridge.entertainment

import dev.autobridge.browser.BrowserUserAgentCodec
import org.junit.Assert.*
import org.junit.Test

class ContentAddressTest {
    // The real Android WebView default: the embedded markers ("; wv", "Version/4.0") plus the
    // frozen model token Chrome for Android does not send.
    private val webViewDefault =
        "Mozilla/5.0 (Linux; Android 14; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/4.0 Chrome/131.0.6778.81 Mobile Safari/537.36"

    @Test fun mobileIdentityIsRebuiltIntoTheShapeChromeForAndroidSends() {
        assertEquals(
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.6778.81 Mobile Safari/537.36",
            BrowserUserAgentCodec.mobile(webViewDefault)
        )
    }

    /**
     * The OEM fragment is why the UA is rebuilt rather than token-stripped: `; SM-S911B
     * Build/UP1A...` varies per device, so no fixed replace chain removes it.
     */
    @Test fun mobileIdentityDropsTheOemModelAndBuildFragment() {
        val samsung = "Mozilla/5.0 (Linux; Android 14; SM-S911B Build/UP1A.231005.007; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.6778.81 Mobile Safari/537.36"
        val rebuilt = BrowserUserAgentCodec.mobile(samsung)
        assertEquals(
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.6778.81 Mobile Safari/537.36",
            rebuilt
        )
    }

    /** No marker Google reads as "embedded" survives, whichever platform UA it started from. */
    @Test fun mobileIdentityCarriesNoEmbeddedWebViewMarker() {
        for (input in listOf(webViewDefault, BrowserUserAgentCodec.mobile(webViewDefault))) {
            val rebuilt = BrowserUserAgentCodec.mobile(input)
            assertFalse(rebuilt.contains("wv"))
            assertFalse(rebuilt.contains("Version/"))
            assertFalse(rebuilt.contains("Build/"))
        }
    }

    /** Rebuilding an already-rebuilt UA has to be a no-op, or a re-apply would erode it. */
    @Test fun mobileIdentityIsStableWhenAppliedTwice() {
        val once = BrowserUserAgentCodec.mobile(webViewDefault)
        assertEquals(once, BrowserUserAgentCodec.mobile(once))
    }

    @Test fun desktopIdentityTracksTheInstalledWebViewsChromeVersion() {
        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.6778.81 Safari/537.36",
            BrowserUserAgentCodec.desktop(webViewDefault)
        )
    }

    /**
     * The fallback version is meant to be bumped as the shipped WebView line moves on, so this
     * asserts the *shape* rather than pinning the number a second time.
     */
    @Test fun desktopIdentityStillReportsAChromeVersionWhenThePlatformNamesNone() {
        val desktop = BrowserUserAgentCodec.desktop("Mozilla/5.0 (Linux; Android 14)")
        assertTrue(desktop.startsWith("Mozilla/5.0 (X11; Linux x86_64) "))
        assertTrue(desktop, Regex("""Chrome/\d+(\.\d+)*""").containsMatchIn(desktop))
    }

    @Test
    fun customUserAgentRejectsBlankControlCharactersAndOversizedValues() {
        assertEquals("Example/1.0", BrowserUserAgentCodec.normalizeCustom("  Example/1.0  "))
        assertEquals(null, BrowserUserAgentCodec.normalizeCustom("   "))
        assertEquals(null, BrowserUserAgentCodec.normalizeCustom("Example\nInjected"))
        assertEquals(null, BrowserUserAgentCodec.normalizeCustom("x".repeat(513)))
    }
    @Test fun normalizesBareHostAndPreservesStreamQuery() {
        assertEquals("https://example.com/live.m3u8?token=a%2Fb", ContentAddress.https(" example.com/live.m3u8?token=a%2Fb "))
    }
    @Test fun blocksNonWebSchemesAndCredentials() {
        listOf("", "http://example.com", "file:///etc/passwd", "javascript:alert(1)",
            "content://media/video", "https://user:pass@example.com", "https://", "https://hello world")
            .forEach { assertNull(it, ContentAddress.https(it)) }
    }
    @Test fun encodesSearchAsOneQueryValue() {
        assertEquals("https://m.youtube.com/results?search_query=rock+%26+roll", ContentAddress.youtubeSearch("rock & roll"))
        assertFalse(ContentAddress.youtubeSearch("ไทย & x=y").contains("&x="))
    }
}
