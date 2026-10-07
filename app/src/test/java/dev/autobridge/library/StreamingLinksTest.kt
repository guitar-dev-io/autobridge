package dev.autobridge.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingLinksTest {
    @Test fun theChineseSitesAreThere() {
        val titles = StreamingLinks.all.filter { it.group == StreamingGroup.CHINESE }.map { it.title }
        assertTrue(titles.containsAll(listOf("Tencent Video", "iQIYI", "Youku", "Mango TV", "CCTV", "Douyin", "Xiaohongshu")))
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

    @Test fun onlyTheChineseGroupIsWarnedAbout() {
        assertTrue(StreamingLinks.all.filter { StreamingLinks.mayNotPlay(it) }.all { it.group == StreamingGroup.CHINESE })
        assertFalse(StreamingLinks.mayNotPlay(StreamingLinks.all.first { it.title == "YouTube" }))
    }
}

class StreamingIconsTest {
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
