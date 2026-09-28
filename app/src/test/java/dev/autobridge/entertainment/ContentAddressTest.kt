package dev.autobridge.entertainment

import dev.autobridge.browser.BrowserUserAgentCodec
import org.junit.Assert.*
import org.junit.Test

class ContentAddressTest {
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
