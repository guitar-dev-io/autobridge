package dev.autobridge.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-parsing coverage for the IPTV source layer. Both classes under test are deliberately free
 * of Android APIs so the URL shapes providers hand out can be pinned down without a device.
 */
class IptvParsingTest {

    @Test
    fun `parses a full player_api link`() {
        val credentials = XtreamCredentials.parse(
            "http://portal.example.com:8080/player_api.php?username=alice&password=s3cret"
        )
        assertEquals("http://portal.example.com:8080", credentials?.portal)
        assertEquals("alice", credentials?.username)
        assertEquals("s3cret", credentials?.password)
    }

    @Test
    fun `parses a get_php playlist link`() {
        val credentials = XtreamCredentials.parse(
            "http://host.tv/get.php?username=bob&password=pw&type=m3u_plus&output=ts"
        )
        assertEquals("http://host.tv", credentials?.portal)
        assertEquals("bob", credentials?.username)
    }

    @Test
    fun `keeps a directory prefix but drops the endpoint file`() {
        val credentials = XtreamCredentials.parse(
            "http://host.tv/iptv/player_api.php?username=u&password=p"
        )
        assertEquals("http://host.tv/iptv", credentials?.portal)
    }

    @Test
    fun `typed credentials win over the ones embedded in a link`() {
        val credentials = XtreamCredentials.parse(
            "http://host.tv/player_api.php?username=stale&password=old", "fresh", "new"
        )
        assertEquals("fresh", credentials?.username)
        assertEquals("new", credentials?.password)
    }

    @Test
    fun `a bare portal without credentials is rejected`() {
        assertNull(XtreamCredentials.parse("http://host.tv"))
    }

    @Test
    fun `a bare host defaults to http`() {
        val credentials = XtreamCredentials.parse("host.tv:8080", "u", "p")
        assertEquals("http://host.tv:8080", credentials?.portal)
    }

    @Test
    fun `a non-http scheme is rejected`() {
        assertNull(XtreamCredentials.parse("ftp://host.tv/player_api.php?username=u&password=p"))
    }

    @Test
    fun `stream urls escape reserved characters in credentials`() {
        val credentials = XtreamCredentials("http://host.tv", "a b", "p/w")
        assertEquals("http://host.tv/live/a%20b/p%2Fw/42.m3u8", credentials.liveUrl("42"))
        assertEquals("http://host.tv/movie/a%20b/p%2Fw/7.mkv", credentials.vodUrl("7", "mkv"))
    }

    @Test
    fun `vod falls back to mp4 when the portal gives no container`() {
        assertTrue(XtreamCredentials("http://h", "u", "p").vodUrl("1", "").endsWith("/1.mp4"))
    }

    @Test
    fun `parses m3u_plus attributes and names`() {
        val channels = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="one" tvg-logo="http://logo/1.png" group-title="News",Channel One
            http://host/stream/1.ts
            #EXTINF:-1 group-title="Music",Radio Two
            http://host/stream/2.aac
            """.trimIndent()
        )
        assertEquals(2, channels.size)
        assertEquals("Channel One", channels[0].name)
        assertEquals("News", channels[0].group)
        assertEquals("http://logo/1.png", channels[0].logo)
        assertEquals("http://host/stream/2.aac", channels[1].url)
    }

    @Test
    fun `a comma inside an attribute does not split the name`() {
        val channels = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="News, World",BBC One HD
            http://host/1
            """.trimIndent()
        )
        assertEquals("BBC One HD", channels.single().name)
        assertEquals("News, World", channels.single().group)
    }

    @Test
    fun `EXTGRP refines the group of the pending entry`() {
        val channels = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1,Sport HD
            #EXTGRP:Sports
            http://host/3
            """.trimIndent()
        )
        assertEquals("Sports", channels.single().group)
    }

    @Test
    fun `an EXTINF without a following url is dropped`() {
        val channels = M3uParser.parse("#EXTM3U\n#EXTINF:-1,Dangling")
        assertTrue(channels.isEmpty())
    }

    /**
     * radio-browser.info serves station lists in this shape: a duration of 1, no attributes at all,
     * a UUID comment between entries and a semicolon in the stream path.
     */
    @Test
    fun `a station list with no attributes and interleaved comments parses`() {
        val channels = M3uParser.parse(
            """
            #EXTM3U
            #RADIOBROWSERUUID:133ed697-1d6d-4b44-ade5-34070ef6aa6d
            #EXTINF:1,101 kiss fm สุราษฏร์ธานี
            http://siamtoday.in.th/8498

            #RADIOBROWSERUUID:153237c9-f6b1-4079-9a78-8063d7730aaf
            #EXTINF:1,101.5 Chula Radio
            http://radio11.plathong.net:7590/;stream.mp3
            """.trimIndent()
        )
        assertEquals(2, channels.size)
        assertEquals("101 kiss fm สุราษฏร์ธานี", channels[0].name)
        assertEquals("http://radio11.plathong.net:7590/;stream.mp3", channels[1].url)
        assertEquals("", channels[1].group)
    }

    /** iptv-org files a channel under several groups at once; the whole label is one category. */
    @Test
    fun `a multi-valued group title is kept verbatim`() {
        val channels = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="AXNAsia.sg@Thailand" group-title="Movies;Series",AXN Asia Thailand (720p)
            http://58.8.186.128:10007/bysid/2814
            """.trimIndent()
        )
        assertEquals("Movies;Series", channels.single().group)
        assertEquals("AXN Asia Thailand (720p)", channels.single().name)
    }

    // ----- Free-TV playlist conventions -----

    /**
     * A real Free-TV block: country groups, `tvg-*` attributes AutoBridge does not show, and the
     * circled-letter notes glued onto the display names.
     */
    @Test
    fun `a Free-TV block parses with its markers read off the names`() {
        val channels = M3uParser.parse(
            """
            #EXTM3U x-tvg-url="https://epgshare01.online/epgshare01/epg_ripper_AL1.xml.gz"
            #EXTINF:-1 tvg-name="Kanali 7" tvg-logo="https://i.imgur.com/rL2v9pM.png" tvg-id="Kanali7.al" tvg-country="AL" group-title="Albania",Kanali 7 Ⓢ
            https://fe.tring.al/delta/105/out/u/1200_1.m3u8
            #EXTINF:-1 tvg-name="ABC News Albania" tvg-logo="https://i.imgur.com/aObcudw.png" tvg-id="ABCNewsAlbania.al" tvg-country="AL" group-title="Albania",ABC News Albania Ⓣ
            https://www.twitch.tv/abcnewsal
            """.trimIndent()
        )
        assertEquals(2, channels.size)
        assertEquals("Albania", channels[0].group)
        assertEquals("https://i.imgur.com/rL2v9pM.png", channels[0].logo)

        val sd = IptvPlaylistConventions.label(channels[0].name)
        assertEquals("Kanali 7", sd.title)
        assertEquals(listOf("SD"), sd.hints)

        val twitch = IptvPlaylistConventions.label(channels[1].name)
        assertEquals("ABC News Albania", twitch.title)
        assertEquals(listOf("Twitch"), twitch.hints)
    }

    @Test
    fun `several markers on one name are all reported`() {
        val label = IptvPlaylistConventions.label("Encuentro Ⓨ Ⓖ")
        assertEquals("Encuentro", label.title)
        assertEquals(listOf("YouTube", "Geo-blocked"), label.hints)
    }

    @Test
    fun `a name without markers is left exactly as it is`() {
        val label = IptvPlaylistConventions.label("101 kiss fm สุราษฏร์ธานี")
        assertEquals("101 kiss fm สุราษฏร์ธานี", label.title)
        assertTrue(label.hints.isEmpty())
    }

    @Test
    fun `watch pages are separated from streams`() {
        assertTrue(IptvPlaylistConventions.isWebPage("https://www.youtube.com/@EuronewsAlbania/live"))
        assertTrue(IptvPlaylistConventions.isWebPage("https://www.twitch.tv/topnewsal"))
        assertTrue(IptvPlaylistConventions.isWebPage("http://youtu.be/abc123"))
        assertFalse(IptvPlaylistConventions.isWebPage("https://fe.tring.al/delta/105/out/u/1200_1.m3u8"))
    }

    /**
     * Provider endpoints hide behind `.php` and `.htm` URLs, and a stream URL may mention a page
     * host in its query. Guessing from either would break channels that work.
     */
    @Test
    fun `an endpoint that is not a watch page stays a stream`() {
        assertFalse(IptvPlaylistConventions.isWebPage("https://sktv.mxnticek.eu/new/stream.php?ch=Nova"))
        assertFalse(
            IptvPlaylistConventions.isWebPage(
                "https://mediapolis.rai.it/relinker/relinkerServlet.htm?cont=2606803&output=7"
            )
        )
        assertFalse(IptvPlaylistConventions.isWebPage("https://cdn.example.com/x.m3u8?ref=youtube.com"))
        assertFalse(IptvPlaylistConventions.isWebPage("rtmp://host/live"))
    }

    /** The directory only hands out addresses; every offer has to be a usable playlist URL. */
    @Test
    fun `every public list offer is an http playlist for its own section`() {
        IptvKind.entries.forEach { kind ->
            val offers = IptvDirectory.list(kind)
            assertTrue("no offer for $kind", offers.isNotEmpty())
            offers.forEach { offer ->
                assertEquals(kind, offer.kind)
                assertTrue(offer.url, offer.url.startsWith("https://"))
                val source = IptvDirectory.toSource(offer, "src-test")
                assertEquals(IptvSourceType.M3U, source.type)
                assertEquals(kind, source.kind)
                assertEquals(offer.url, source.url)
            }
        }
        assertTrue(
            IptvDirectory.list(IptvKind.TV).any {
                it.url == "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"
            }
        )
    }

    // ----- Default sources -----

    /** A fresh install has to open with something to browse in both sections. */
    @Test
    fun `a fresh install seeds a default list for every section`() {
        val defaults = IptvDirectory.pendingDefaults(seededUrls = emptySet(), existingUrls = emptySet())
        assertEquals(IptvDirectory.defaults(), defaults)
        IptvKind.entries.forEach { kind ->
            assertTrue("no default for $kind", defaults.any { it.kind == kind })
        }
        assertTrue(defaults.all { it.seeded && it.url.startsWith("https://") })
    }

    /**
     * The point of remembering seeded URLs rather than a single "has run" flag: removing a default
     * has to stick across every later read.
     */
    @Test
    fun `a removed default is never recreated`() {
        val removed = IptvDirectory.defaults().first()
        val kept = IptvDirectory.defaults().drop(1)
        val pending = IptvDirectory.pendingDefaults(
            seededUrls = IptvDirectory.defaults().map { it.url }.toSet(),
            existingUrls = kept.map { it.url }.toSet()
        )
        assertTrue(pending.isEmpty())
        assertFalse(pending.contains(removed))
    }

    /** A default added by a later version is recorded nowhere yet, so it still arrives. */
    @Test
    fun `a default introduced later is still seeded`() {
        val old = IptvDirectory.defaults().drop(1)
        val pending = IptvDirectory.pendingDefaults(
            seededUrls = old.map { it.url }.toSet(),
            existingUrls = old.map { it.url }.toSet()
        )
        assertEquals(listOf(IptvDirectory.defaults().first()), pending)
    }

    /** A default the user had already added by hand is not duplicated. */
    @Test
    fun `a default already configured by hand is not added twice`() {
        val pending = IptvDirectory.pendingDefaults(
            seededUrls = emptySet(),
            existingUrls = IptvDirectory.defaults().map { it.url }.toSet()
        )
        assertTrue(pending.isEmpty())
    }
}
