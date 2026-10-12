package dev.autobridge.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.StringReader
import java.util.Base64

/** The programme guide's decisions: short guides, now/next, XMLTV reading, file choice, matching. */
class IptvGuideTest {
    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

    private fun listing(title: String, start: Long, stop: Long) =
        """{"title":"$title","start_timestamp":"$start","stop_timestamp":"$stop"}"""

    private fun live(title: String, guideId: String = "", country: String = "") = IptvEntry(
        id = "m3u:$title", title = title, categoryId = "x", type = IptvEntryType.LIVE,
        url = "http://example.com/$title.m3u8", guideId = guideId, country = country
    )

    // ----- Xtream short guide -----

    @Test fun `short guide gives now and next`() {
        val json = """{"epg_listings":[${listing(b64("News"), 100, 200)},${listing(b64("Drama"), 200, 300)},${listing(b64("Film"), 300, 400)}]}"""
        val guide = IptvGuideRules.nowNext(IptvGuideRules.parseShortEpg(json), nowMs = 250_000)
        assertEquals("Drama", guide.now?.title)
        assertEquals("Film", guide.next?.title)
    }

    @Test fun `short guide reads thai and plain titles`() {
        val json = """{"epg_listings":[${listing(b64("ข่าวเย็น"), 0, 10)},${listing("Live Football", 10, 20)}]}"""
        val programmes = IptvGuideRules.parseShortEpg(json)
        assertEquals(listOf("ข่าวเย็น", "Live Football"), programmes.map { it.title })
    }

    @Test fun `short guide without listings is empty`() {
        assertTrue(IptvGuideRules.parseShortEpg("""{"epg_listings":[]}""").isEmpty())
        assertTrue(IptvGuideRules.parseShortEpg("[]").isEmpty())
    }

    // ----- now / next -----

    @Test fun `a gap in the guide still names what is next`() {
        val programmes = listOf(IptvProgramme("Morning", 0, 100), IptvProgramme("Evening", 200, 300))
        val guide = IptvGuideRules.nowNext(programmes, nowMs = 150)
        assertNull(guide.now)
        assertEquals("Evening", guide.next?.title)
    }

    @Test fun `after the last listing there is nothing to show`() {
        val guide = IptvGuideRules.nowNext(listOf(IptvProgramme("Old", 0, 100)), nowMs = 500)
        assertTrue(guide.isEmpty)
    }

    @Test fun `progress runs from start to stop`() {
        assertEquals(0.25f, IptvProgramme("A", 0, 400).progress(100), 0.001f)
        assertEquals(1f, IptvProgramme("A", 0, 400).progress(900), 0.001f)
    }

    // ----- XMLTV -----

    private val xmltv = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <tv generator-info-name="test">
          <channel id="3HD.th"><display-name lang="en">3HD</display-name></channel>
          <channel id="Amarin.TV.th"><display-name>Amarin TV HD</display-name><url>http://x</url></channel>
          <programme start="20261012180000 +0700" stop="20261012190000 +0700" channel="3HD.th">
            <title lang="th">ข่าวภาคค่ำ</title><desc>long text</desc><category>News</category>
          </programme>
          <programme start="20261012190000 +0700" stop="20261012200000 +0700" channel="3HD.th">
            <title>Drama &amp; More</title>
          </programme>
          <programme start="20261001000000 +0700" stop="20261001010000 +0700" channel="3HD.th">
            <title>Last week</title>
          </programme>
          <programme start="20261012183000 +0700" stop="20261012193000 +0700" channel="Amarin.TV.th">
            <title>Talk show</title>
          </programme>
        </tv>
    """.trimIndent()

    private fun parse(text: String, fromMs: Long, untilMs: Long): XmlTvParser.Guide {
        val parser = KXmlParser()
        parser.setInput(StringReader(text))
        return XmlTvParser.parse(parser, fromMs, untilMs)
    }

    private val sixThirtyBangkok = XmlTvParser.parseTime("20261012183000 +0700")!!

    @Test fun `xmltv times honour their offset`() {
        assertEquals(XmlTvParser.parseTime("20261012113000 +0000"), sixThirtyBangkok)
        assertEquals(XmlTvParser.parseTime("20261012113000"), sixThirtyBangkok)
        assertNull(XmlTvParser.parseTime("tomorrow"))
    }

    @Test fun `xmltv keeps the window and reads titles`() {
        val guide = parse(xmltv, sixThirtyBangkok - 3_600_000, sixThirtyBangkok + 36 * 3_600_000L)
        val threeHd = guide.programmesFor(IptvGuideRules.matchKeys(live("3HD", guideId = "3HD.th")))
        assertEquals(listOf("ข่าวภาคค่ำ", "Drama & More"), threeHd.map { it.title })
        assertEquals("News", threeHd.first().category)
        val nowNext = IptvGuideRules.nowNext(threeHd, sixThirtyBangkok)
        assertEquals("ข่าวภาคค่ำ", nowNext.now?.title)
        assertEquals("Drama & More", nowNext.next?.title)
    }

    @Test fun `a channel is found by name when the playlist uses another id`() {
        val guide = parse(xmltv, sixThirtyBangkok - 3_600_000, sixThirtyBangkok + 3_600_000)
        val amarin = live("Amarin TV (1080p)", guideId = "AmarinTV.th")
        assertEquals("Talk show", guide.programmesFor(IptvGuideRules.matchKeys(amarin)).single().title)
    }

    @Test fun `an html error page is not a guide`() {
        val failed = runCatching {
            parse("<!DOCTYPE html><html><head><meta charset=utf-8><title>404</title></head></html>", 0, Long.MAX_VALUE)
        }
        assertTrue(failed.isFailure || failed.getOrThrow().isEmpty)
    }

    // ----- which files to fetch -----

    @Test fun `a typed guide wins over the playlist`() {
        val urls = IptvGuideRules.guideUrls("https://mine/guide.xml", listOf("https://theirs/a.xml"), "", emptyList())
        assertEquals(listOf("https://mine/guide.xml"), urls)
    }

    @Test fun `the directory fallback follows the declared guide`() {
        val urls = IptvGuideRules.guideUrls("", listOf("https://dead/guide.xml.gz"), "https://good/TH1.xml.gz", emptyList())
        assertEquals(listOf("https://dead/guide.xml.gz", "https://good/TH1.xml.gz"), urls)
    }

    @Test fun `a catalogue of guides is narrowed to the countries in view`() {
        val declared = (listOf("AL1", "ALL_SOURCES1", "TH1", "US1", "UK1")).map {
            "https://epgshare01.online/epgshare01/epg_ripper_$it.xml.gz"
        }
        val urls = IptvGuideRules.guideUrls("", declared, "", listOf(live("3HD", guideId = "3HD.th")))
        assertEquals(listOf("https://epgshare01.online/epgshare01/epg_ripper_TH1.xml.gz"), urls)
        // tvg-country counts as well as the id's suffix.
        val us = IptvGuideRules.guideUrls("", declared, "", listOf(live("Fox", country = "US")))
        assertEquals(listOf("https://epgshare01.online/epgshare01/epg_ripper_US1.xml.gz"), us)
    }

    @Test fun `a catalogue with no country match fetches nothing`() {
        val declared = (1..10).map { "https://example.com/guide_part$it.xml" }
        assertTrue(IptvGuideRules.guideUrls("", declared, "", listOf(live("No id"))).isEmpty())
    }

    // ----- matching -----

    @Test fun `names lose case punctuation and quality tags`() {
        assertEquals(IptvGuideRules.normalizeName("Amarin TV HD"), IptvGuideRules.normalizeName("amarin-tv (1080p)"))
        assertEquals("ช่อง7", IptvGuideRules.normalizeName("ช่อง 7 HD"))
    }

    @Test fun `iptv-org feed suffixes are matched without them`() {
        val keys = IptvGuideRules.matchKeys(live("00s Replay", guideId = "00sReplay.us@SD"))
        assertTrue("00sreplay.us" in keys)
    }

    // ----- playlist header -----

    @Test fun `the playlist header names its guides`() {
        val text = "#EXTM3U x-tvg-url=\"https://a/1.xml.gz, https://a/2.xml.gz\" url-tvg=\"https://b/g.xml\"\n" +
            "#EXTINF:-1 tvg-id=\"3HD.th\" tvg-country=\"TH\",3HD\nhttp://s/3hd.m3u8\n"
        assertEquals(listOf("https://a/1.xml.gz", "https://a/2.xml.gz", "https://b/g.xml"), M3uParser.guideUrls(text))
        val channel = M3uParser.parse(text).single()
        assertEquals("3HD.th", channel.guideId)
        assertEquals("TH", channel.country)
    }

    @Test fun `a playlist without a header has no guide`() {
        assertTrue(M3uParser.guideUrls("#EXTINF:-1,One\nhttp://x\n").isEmpty())
    }
}
