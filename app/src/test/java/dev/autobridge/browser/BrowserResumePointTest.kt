package dev.autobridge.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserResumePointTest {
    private val watch = "https://m.youtube.com/watch?v=abc123"

    /** A ten-minute clip, so the tail guard is nowhere near the positions used below. */
    private fun resume(url: String, positionMs: Long, durationMs: Long = 600_000) =
        BrowserResumePoint.withResumeAt(url, positionMs, durationMs)

    @Test fun aPlayingWatchPageCarriesItsPositionInSeconds() {
        assertEquals("$watch&t=200", resume(watch, 200_400))
        // Rounds down to the second: a fraction is not worth the extra characters.
        assertEquals("$watch&t=200", resume(watch, 200_999))
    }

    @Test fun watchPagesOnEveryYouTubeHostAreRecognised() {
        for (host in listOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")) {
            val url = "https://$host/watch?v=abc123"
            assertTrue(host, BrowserResumePoint.supports(url))
            assertEquals("$url&t=90", resume(url, 90_000))
        }
        // Short links work the same way, with no query of their own to merge into.
        assertEquals("https://youtu.be/abc123?t=90", resume("https://youtu.be/abc123", 90_000))
    }

    @Test fun pagesWithNoResumeChannelAreLeftExactlyAsTheyAre() {
        for (url in listOf(
            "https://m.youtube.com/shorts/abc123",          // `t` is ignored on Shorts
            "https://m.youtube.com/results?search_query=x", // not a video at all
            "https://m.youtube.com/watch?list=PL1",         // a watch URL with no video id
            "https://www.netflix.com/watch/80100172",       // another site's watch page
            "https://example.com/video.html",
        )) {
            assertFalse(url, BrowserResumePoint.supports(url))
            assertEquals(url, resume(url, 200_000))
        }
    }

    @Test fun anExistingTimestampIsReplacedRatherThanDuplicated() {
        assertEquals(
            "https://m.youtube.com/watch?v=abc123&t=200",
            resume("https://m.youtube.com/watch?v=abc123&t=30", 200_000)
        )
        // Position in the middle of the query, and every other parameter kept in place.
        assertEquals(
            "https://m.youtube.com/watch?v=abc123&list=PL1&t=200",
            resume("https://m.youtube.com/watch?v=abc123&t=30&list=PL1", 200_000)
        )
    }

    @Test fun theFragmentSurvivesTheRewrite() {
        assertEquals("$watch&t=200#comments", resume("$watch#comments", 200_000))
    }

    @Test fun thereIsNothingWorthResumingAtTheVeryStart() {
        assertEquals(watch, resume(watch, 0))
        assertEquals(watch, resume(watch, 4_999))
        assertEquals("$watch&t=5", resume(watch, 5_000))
    }

    @Test fun aFinishedVideoStartsOverOnTheCarInsteadOfOpeningAtItsEnd() {
        assertEquals(watch, resume(watch, 180_000, durationMs = 180_000))
        assertEquals(watch, resume(watch, 171_000, durationMs = 180_000))
        assertEquals("$watch&t=169", resume(watch, 169_000, durationMs = 180_000))
    }

    @Test fun aStreamWithNoKnownDurationIsSentUnchanged() {
        // duration 0 is the live edge and the not-yet-loaded player: a position means nothing to a
        // page that is about to load fresh.
        assertEquals(watch, resume(watch, 600_000, durationMs = 0))
    }

    @Test fun theConfirmationReadsTheWayAPlayerPrintsTheTime() {
        assertEquals("0:05", BrowserResumePoint.clock(5_000))
        assertEquals("3:20", BrowserResumePoint.clock(200_400))
        assertEquals("1:00:00", BrowserResumePoint.clock(3_600_000))
        assertEquals("1:02:03", BrowserResumePoint.clock(3_723_000))
        assertEquals("0:00", BrowserResumePoint.clock(-1))
    }

    @Test fun malformedInputIsNotTreatedAsAVideo() {
        for (url in listOf("", "not a url", "https://", "javascript:alert(1)")) {
            assertFalse(url, BrowserResumePoint.supports(url))
            assertEquals(url, resume(url, 200_000))
        }
    }
}
