package dev.autobridge.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSiteEntryTest {
    private val youtubeTile = "https://m.youtube.com"
    private val musicTile = "https://music.youtube.com"
    private val watching = "https://m.youtube.com/watch?v=abc123"

    @Test fun tappingTheTileOfTheSiteAlreadyOpenReturnsToItInsteadOfReloading() {
        assertTrue(BrowserSiteEntry.resumes(watching, youtubeTile))
        // The same tile with the trailing slash the shortcut lists sometimes carry.
        assertTrue(BrowserSiteEntry.resumes(watching, "https://m.youtube.com/"))
    }

    @Test fun theMobileAndDesktopFrontEndsOfOneSiteAreTheSameSite() {
        // Which host is loaded follows the user-agent identity, not anything the driver picked.
        for (open in listOf(
            "https://m.youtube.com/watch?v=abc123",
            "https://www.youtube.com/watch?v=abc123",
            "https://youtube.com/watch?v=abc123",
        )) {
            assertTrue(open, BrowserSiteEntry.resumes(open, youtubeTile))
            assertTrue(open, BrowserSiteEntry.resumes(open, "https://www.youtube.com"))
        }
    }

    @Test fun youTubeMusicStaysASiteOfItsOwn() {
        // Only "m." and "www." collapse, so the Music tile still switches away from a video.
        assertFalse(BrowserSiteEntry.resumes(watching, musicTile))
        assertFalse(BrowserSiteEntry.resumes("https://music.youtube.com/watch?v=abc123", youtubeTile))
        assertTrue(BrowserSiteEntry.resumes("https://music.youtube.com/playlist?list=PL1", musicTile))
    }

    @Test fun aTileForAnotherSiteAlwaysLoads() {
        assertFalse(BrowserSiteEntry.resumes(watching, "https://www.google.com"))
        assertFalse(BrowserSiteEntry.resumes(watching, "https://www.twitch.tv"))
        // A host that merely ends with the same text is a different site.
        assertFalse(BrowserSiteEntry.resumes(watching, "https://notyoutube.com"))
        assertFalse(BrowserSiteEntry.resumes("https://youtube.com.evil.example/", youtubeTile))
    }

    @Test fun aLinkToOneSpecificPageAlwaysLoadsEvenOnTheSiteAlreadyOpen() {
        // Bookmarks, quick-launch shortcuts and channel links: the driver asked for that page.
        for (target in listOf(
            "https://m.youtube.com/watch?v=zzz999",
            "https://m.youtube.com/feed/subscriptions",
            "https://m.youtube.com/#shorts",
        )) {
            assertFalse(target, BrowserSiteEntry.resumes(watching, target))
        }
    }

    @Test fun withNoPageLoadedYetThereIsNothingToReturnTo() {
        assertFalse(BrowserSiteEntry.resumes(null, youtubeTile))
        assertFalse(BrowserSiteEntry.resumes("", youtubeTile))
        assertFalse(BrowserSiteEntry.resumes("   ", youtubeTile))
        assertFalse(BrowserSiteEntry.resumes("about:blank", youtubeTile))
    }

    @Test fun malformedInputNeverResumes() {
        assertFalse(BrowserSiteEntry.resumes("not a url", youtubeTile))
        assertFalse(BrowserSiteEntry.resumes(watching, "not a url"))
        assertFalse(BrowserSiteEntry.resumes(watching, ""))
    }
}
