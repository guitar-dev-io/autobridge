package dev.autobridge.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the share sheet actually hands over, as opposed to what a URL field would.
 *
 * Every case here is a real shape seen from a sharing app: YouTube's prose-plus-short-link, a
 * browser's bare URL with a separate subject, a chat message with a link in the middle, and the
 * several ways there is no link at all.
 */
class ShareIntakeTest {

    @Test
    fun `a bare url is taken as is`() {
        val source = ShareIntake.parse("https://example.com/page")
        assertEquals("https://example.com/page", source?.url)
        assertEquals(BridgeSource.Origin.SHARE, source?.origin)
    }

    @Test
    fun `a link inside prose is extracted`() {
        val source = ShareIntake.parse("Look at this https://example.com/page it is good")
        assertEquals("https://example.com/page", source?.url)
    }

    @Test
    fun `a trailing full stop is not part of the link`() {
        assertEquals(
            "https://example.com/page",
            ShareIntake.parse("Watch https://example.com/page.")?.url
        )
    }

    @Test
    fun `a youtube short link becomes a watch url`() {
        // The rest of the app recognises watch URLs — the resume-point rewriter above all — and a
        // share from the YouTube app is the commonest way a link gets here.
        assertEquals(
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
            ShareIntake.parse("https://youtu.be/dQw4w9WgXcQ")?.url
        )
    }

    @Test
    fun `a short link keeps its start time`() {
        assertEquals(
            "https://m.youtube.com/watch?v=abc123&t=90",
            ShareIntake.parse("https://youtu.be/abc123?t=90")?.url
        )
    }

    @Test
    fun `the youtube share format is handled whole`() {
        val shared = "Check out this video\n\nhttps://youtu.be/abc123"
        val source = ShareIntake.parse(shared, subject = "Check out this video")
        assertEquals("https://m.youtube.com/watch?v=abc123", source?.url)
        assertEquals("Check out this video", source?.title)
    }

    @Test
    fun `a subject that merely repeats the url is not used as a title`() {
        val source = ShareIntake.parse("https://example.com/p", subject = "https://example.com/p")
        assertEquals("", source?.title)
    }

    @Test
    fun `a bare host is accepted`() {
        // The address bar accepts one, so refusing it here would make the share sheet stricter
        // than the thing it feeds.
        assertEquals("https://example.com/watch", ShareIntake.parse("example.com/watch")?.url)
    }

    @Test
    fun `text with no link yields nothing`() {
        assertNull(ShareIntake.parse("just some words"))
        assertNull(ShareIntake.parse(""))
        assertNull(ShareIntake.parse(null))
    }

    @Test
    fun `credentials in a url are refused`() {
        assertNull(ShareIntake.normalize("https://user:pass@example.com/x"))
    }

    @Test
    fun `non-web schemes are refused`() {
        assertNull(ShareIntake.normalize("file:///etc/passwd"))
        assertNull(ShareIntake.normalize("javascript:alert(1)"))
        assertNull(ShareIntake.normalize("intent://evil#Intent;end"))
    }

    @Test
    fun `a very long shared paragraph does not become a title`() {
        val prose = "word ".repeat(60)
        val source = ShareIntake.parse("$prose https://example.com/x")
        assertTrue(source?.title.isNullOrEmpty())
    }

    @Test
    fun `the display host drops the mobile and www prefixes`() {
        assertEquals("youtube.com", BridgeSource("https://m.youtube.com/watch?v=1").displayHost)
        assertEquals("example.com", BridgeSource("https://www.example.com/").displayHost)
    }
}
