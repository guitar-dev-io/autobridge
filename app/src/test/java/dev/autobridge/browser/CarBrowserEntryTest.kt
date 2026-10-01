package dev.autobridge.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarBrowserEntryTest {
    private val youtubeTile = "https://m.youtube.com"
    private val musicTile = "https://music.youtube.com"
    private val watching = "https://m.youtube.com/watch?v=abc123"

    @Test fun tappingTheTileOfTheSiteAlreadyOpenReturnsToItInsteadOfReloading() {
        assertTrue(CarBrowserEntry.resumes(watching, youtubeTile))
        // The same tile with the trailing slash the shortcut lists sometimes carry.
        assertTrue(CarBrowserEntry.resumes(watching, "https://m.youtube.com/"))
    }

    @Test fun theMobileAndDesktopFrontEndsOfOneSiteAreTheSameSite() {
        // Which host is loaded follows the user-agent identity, not anything the driver picked.
        for (open in listOf(
            "https://m.youtube.com/watch?v=abc123",
            "https://www.youtube.com/watch?v=abc123",
            "https://youtube.com/watch?v=abc123",
        )) {
            assertTrue(open, CarBrowserEntry.resumes(open, youtubeTile))
            assertTrue(open, CarBrowserEntry.resumes(open, "https://www.youtube.com"))
        }
    }

    @Test fun youTubeMusicStaysASiteOfItsOwn() {
        // Only "m." and "www." collapse, so the Music tile still switches away from a video.
        assertFalse(CarBrowserEntry.resumes(watching, musicTile))
        assertFalse(CarBrowserEntry.resumes("https://music.youtube.com/watch?v=abc123", youtubeTile))
        assertTrue(CarBrowserEntry.resumes("https://music.youtube.com/playlist?list=PL1", musicTile))
    }

    @Test fun aTileForAnotherSiteAlwaysLoads() {
        assertFalse(CarBrowserEntry.resumes(watching, "https://www.google.com"))
        assertFalse(CarBrowserEntry.resumes(watching, "https://www.twitch.tv"))
        // A host that merely ends with the same text is a different site.
        assertFalse(CarBrowserEntry.resumes(watching, "https://notyoutube.com"))
        assertFalse(CarBrowserEntry.resumes("https://youtube.com.evil.example/", youtubeTile))
    }

    @Test fun aLinkToOneSpecificPageAlwaysLoadsEvenOnTheSiteAlreadyOpen() {
        // Bookmarks, quick-launch shortcuts and channel links: the driver asked for that page.
        for (target in listOf(
            "https://m.youtube.com/watch?v=zzz999",
            "https://m.youtube.com/feed/subscriptions",
            "https://m.youtube.com/#shorts",
        )) {
            assertFalse(target, CarBrowserEntry.resumes(watching, target))
        }
    }

    @Test fun withNoPageLoadedYetThereIsNothingToReturnTo() {
        assertFalse(CarBrowserEntry.resumes(null, youtubeTile))
        assertFalse(CarBrowserEntry.resumes("", youtubeTile))
        assertFalse(CarBrowserEntry.resumes("   ", youtubeTile))
        assertFalse(CarBrowserEntry.resumes("about:blank", youtubeTile))
    }

    @Test fun malformedInputNeverResumes() {
        assertFalse(CarBrowserEntry.resumes("not a url", youtubeTile))
        assertFalse(CarBrowserEntry.resumes(watching, "not a url"))
        assertFalse(CarBrowserEntry.resumes(watching, ""))
    }
}
