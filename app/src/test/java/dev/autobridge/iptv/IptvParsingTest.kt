package dev.autobridge.iptv

import org.junit.Assert.assertEquals
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
}
