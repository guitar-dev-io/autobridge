package dev.autobridge.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingLinksTest {
    @Test fun theChineseGroupIsGone() {
        val gone = listOf("Tencent Video", "Youku", "Mango TV", "CCTV", "Douyin", "Xiaohongshu")
        assertTrue(StreamingLinks.all.none { it.title in gone })
        assertTrue(StreamingGroup.entries.none { it.name == "CHINESE" })
    }

    @Test fun theStreamingServicesLeadInTheirOwnOrder() {
        val titles = StreamingLinks.all.filter { it.group == StreamingGroup.PREMIUM }.map { it.title }
        assertEquals(listOf("Netflix", "Disney+", "Prime Video", "HBO Max", "Viu", "WeTV", "iQIYI"), titles)
        assertEquals(StreamingGroup.PREMIUM, StreamingLinks.grouped().first().first)
    }

    @Test fun everyStreamingServiceIsTheOnesThatNeedChromeAndNothingElseIs() {
        val premium = StreamingLinks.all.filter { it.group == StreamingGroup.PREMIUM }
        assertTrue(premium.all { StreamingLinks.mayNotPlay(it) })
        assertTrue(StreamingLinks.all.filter { it.group != StreamingGroup.PREMIUM }.none { StreamingLinks.mayNotPlay(it) })
    }

    @Test fun generalVideoPlaysInTheBuiltInBrowserAndComesRightAfter() {
        val titles = StreamingLinks.all.filter { it.group == StreamingGroup.VIDEO }.map { it.title }
        assertEquals(listOf("YouTube", "Dailymotion", "Vimeo"), titles)
        assertEquals(StreamingGroup.VIDEO, StreamingLinks.grouped()[1].first)
    }

    @Test fun largeCardsAreTheServicesAndGeneralVideoOnly() {
        val large = StreamingLinks.all.filter { StreamingLinks.isFeatured(it) }.map { it.group }.toSet()
        assertEquals(setOf(StreamingGroup.PREMIUM, StreamingGroup.VIDEO), large)
    }

    @Test fun theShortAndSocialSitesAreTheirOwnGroup() {
        val titles = StreamingLinks.all.filter { it.group == StreamingGroup.SOCIAL }.map { it.title }
        assertEquals(listOf("TikTok", "Facebook Watch"), titles)
    }

    @Test fun theGlobalSitesAreThere() {
        val titles = StreamingLinks.all.map { it.title }
        assertTrue(titles.containsAll(listOf("Facebook Watch", "Dailymotion", "Vimeo", "SoundCloud", "Radio Garden", "Kick")))
    }

    @Test fun aptvIsNotListed() {
        assertFalse(StreamingLinks.all.any { it.title.contains("APTV", ignoreCase = true) || it.url.contains("aptv", ignoreCase = true) })
    }

    @Test fun everyAddressIsHttpsAndUnique() {
        assertTrue(StreamingLinks.all.all { it.url.startsWith("https://") })
        assertEquals(StreamingLinks.all.size, StreamingLinks.all.map { it.url }.toSet().size)
        assertEquals(StreamingLinks.all.size, StreamingLinks.all.map { it.title }.toSet().size)
    }

    @Test fun groupsComeInOrderAndNoneIsEmpty() {
        val groups = StreamingLinks.grouped().map { it.first }
        assertEquals(StreamingGroup.entries.filter { g -> StreamingLinks.all.any { it.group == g } }, groups)
        assertTrue(StreamingLinks.grouped().all { it.second.isNotEmpty() })
    }

    @Test fun theProtectedSitesAreWarnedAbout() {
        val warned = StreamingLinks.all.filter { StreamingLinks.mayNotPlay(it) }.map { it.title }.toSet()
        assertTrue(warned.containsAll(listOf("Netflix", "Disney+", "Prime Video", "HBO Max", "Viu", "WeTV", "iQIYI")))
        // What plays without DRM carries no warning.
        listOf("YouTube", "TikTok", "Vimeo", "SoundCloud", "Twitch").forEach { name ->
            assertFalse("$name should not be warned about", StreamingLinks.mayNotPlay(StreamingLinks.all.first { it.title == name }))
        }
    }
}

class StreamingIconsTest {
    @Test fun theMainGridServicesHaveTheirOwnColours() {
        listOf("Netflix", "Disney+", "Prime Video", "HBO Max", "Viu").forEach { name ->
            val link = StreamingLinks.all.first { it.title == name }
            assertTrue("$name has no icon of its own", StreamingIcons.hasOwnStyle(link))
        }
    }

    @Test fun everyCatalogSiteHasItsOwnMark() {
        // A catalog site never falls back to the generic initial-on-a-palette-colour.
        StreamingLinks.all.forEach { link -> assertTrue("${link.title} has no icon of its own", StreamingIcons.hasOwnStyle(link)) }
    }

    @Test fun anUnknownSiteGetsItsInitialAndAStableColour() {
        val a = StreamingIcons.styleFor("my channel", "https://example.org/live")
        val b = StreamingIcons.styleFor("my channel", "https://example.org/live")
        assertEquals("M", a.glyph)
        assertEquals(a, b)
    }

    @Test fun aNameWithNoLetterStillGetsAMark() {
        assertEquals("•", StreamingIcons.styleFor("---", "https://example.org").glyph)
    }
}
