package dev.autobridge.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The routing table, as behaviour rather than as a comment.
 *
 * "Why did this open in the browser instead of the player" is the question the bridge gets asked
 * most often, so each branch of [ContentRouter] gets a case here and the DRM boundary gets
 * several — it is the one decision where being wrong is a correctness problem rather than a
 * usability one.
 */
class ContentRouterTest {

    private fun source(url: String, mime: String? = null) = BridgeSource(url, mimeType = mime)

    @Test
    fun `direct media goes to the native player`() {
        listOf(
            "https://example.com/clip.mp4",
            "https://example.com/audio.mp3",
            "https://cdn.example.com/live/stream.m3u8",
            "https://cdn.example.com/dash/manifest.mpd",
            "http://example.com/movie.mkv"
        ).forEach { url ->
            assertEquals(url, EngineKind.NATIVE, ContentRouter.route(source(url), remoteStreamAvailable = false))
        }
    }

    @Test
    fun `a query string does not hide the extension`() {
        assertEquals(
            EngineKind.NATIVE,
            ContentRouter.route(source("https://cdn.example.com/a.m3u8?token=abc"), false)
        )
    }

    @Test
    fun `an explicit media mime type wins over a page-shaped path`() {
        assertEquals(
            EngineKind.NATIVE,
            ContentRouter.route(source("https://example.com/watch", mime = "video/mp4"), false)
        )
    }

    @Test
    fun `ordinary pages go to the browser`() {
        listOf(
            "https://m.youtube.com/watch?v=abc",
            "https://en.wikipedia.org/wiki/Car",
            "http://example.com/"
        ).forEach { url ->
            assertEquals(url, EngineKind.BROWSER, ContentRouter.route(source(url), remoteStreamAvailable = false))
        }
    }

    @Test
    fun `a page still goes to the browser when a remote host exists`() {
        // The remote stream is a fallback, never a first choice: it costs an encoder, a network
        // hop and a decoder for something the car renders locally.
        assertEquals(
            EngineKind.BROWSER,
            ContentRouter.route(source("https://example.com/page"), remoteStreamAvailable = true)
        )
    }

    @Test
    fun `a non-web scheme reaches the remote stream only when one is configured`() {
        val odd = source("rtsp://host/stream")
        assertEquals(EngineKind.REMOTE_STREAM, ContentRouter.route(odd, remoteStreamAvailable = true))
        assertEquals(EngineKind.UNSUPPORTED, ContentRouter.route(odd, remoteStreamAvailable = false))
    }

    @Test
    fun `malformed input is refused rather than guessed at`() {
        val decision = ContentRouter.explain(source("not a url at all"), remoteStreamAvailable = true)
        assertEquals(EngineKind.UNSUPPORTED, decision.engine)
        assertEquals(BridgeErrorType.UNSUPPORTED_CONTENT, decision.error)
    }

    // ------------------------------------------------------------------------------- DRM

    @Test
    fun `protected services are refused and reported as protected`() {
        listOf(
            "https://www.netflix.com/watch/80100172",
            "https://netflix.com/title/1",
            "https://www.disneyplus.com/video/x",
            "https://app.primevideo.com/detail/y",
            "https://tv.apple.com/show/z"
        ).forEach { url ->
            val decision = ContentRouter.explain(source(url), remoteStreamAvailable = true)
            assertEquals(url, EngineKind.UNSUPPORTED, decision.engine)
            assertEquals(url, BridgeErrorType.DRM_PROTECTED_CONTENT, decision.error)
            assertEquals(url, ContentRouter.Reason.DRM_PROTECTED, decision.reason)
        }
    }

    @Test
    fun `a protected host is refused even when the URL looks like a direct file`() {
        // The DRM branch sits above the direct-media branch on purpose; if it did not, a
        // `.mp4`-shaped URL on a protected host would be handed to ExoPlayer.
        val decision = ContentRouter.explain(source("https://www.netflix.com/x.mp4"), false)
        assertEquals(EngineKind.UNSUPPORTED, decision.engine)
        assertEquals(BridgeErrorType.DRM_PROTECTED_CONTENT, decision.error)
    }

    @Test
    fun `a protected host never gets a remote-stream fallback`() {
        assertNull(
            ContentRouter.fallback(
                source("https://www.netflix.com/watch/1"),
                EngineKind.BROWSER,
                remoteStreamAvailable = true
            )
        )
    }

    @Test
    fun `a lookalike domain is not treated as protected`() {
        // Suffix matching must be on a domain boundary, or "netflix.com.evil.example" would be
        // refused and, worse, "mynetflix.com" would be too.
        assertFalse(ContentRouter.isProtected("netflix.com.example.org"))
        assertFalse(ContentRouter.isProtected("mynetflix.com"))
        assertTrue(ContentRouter.isProtected("www.netflix.com"))
        assertTrue(ContentRouter.isProtected("NETFLIX.COM"))
    }

    // -------------------------------------------------------------------------- fallback

    @Test
    fun `the browser falls back to a remote stream only when one is configured`() {
        val page = source("https://example.com/page")
        assertEquals(
            EngineKind.REMOTE_STREAM,
            ContentRouter.fallback(page, EngineKind.BROWSER, remoteStreamAvailable = true)
        )
        assertNull(ContentRouter.fallback(page, EngineKind.BROWSER, remoteStreamAvailable = false))
    }

    @Test
    fun `the native player has no fallback`() {
        // A direct URL ExoPlayer refused is a broken or unsupported file, not a page; opening a
        // browser on it would download it or show a blank tab.
        assertNull(
            ContentRouter.fallback(source("https://example.com/a.mp4"), EngineKind.NATIVE, true)
        )
    }

    @Test
    fun `a remote stream does not fall back to itself`() {
        assertNull(
            ContentRouter.fallback(source("https://example.com/page"), EngineKind.REMOTE_STREAM, true)
        )
    }
}
