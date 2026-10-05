package dev.autobridge.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The logo shapes real playlists hand out. [IptvLogos] and [M3uParser] are both free of Android
 * APIs, so every one of them is pinned down here without a device or a network.
 */
class IptvLogoTest {

    private val playlist = "https://lists.example.com/iptv/playlists/thai.m3u"

    @Test
    fun `reads the canonical attribute`() {
        val channels = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-logo="https://cdn.example.com/a.png" group-title="News",Channel A
            https://stream.example.com/a.m3u8
            """.trimIndent()
        )
        assertEquals("https://cdn.example.com/a.png", channels.single().logo)
    }

    @Test
    fun `falls back to the other spellings panels emit`() {
        val channels = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 logo="https://cdn.example.com/b.png",Channel B
            https://stream.example.com/b.m3u8
            #EXTINF:-1 url-logo="https://cdn.example.com/c.png",Channel C
            https://stream.example.com/c.m3u8
            #EXTINF:-1 tvg-logo-small="https://cdn.example.com/d.png",Channel D
            https://stream.example.com/d.m3u8
            """.trimIndent()
        )
        assertEquals(
            listOf(
                "https://cdn.example.com/b.png",
                "https://cdn.example.com/c.png",
                "https://cdn.example.com/d.png"
            ),
            channels.map { it.logo }
        )
    }

    @Test
    fun `the canonical attribute wins when a line carries two`() {
        val channels = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 logo="https://cdn.example.com/wrong.png" tvg-logo="https://cdn.example.com/right.png",E
            https://stream.example.com/e.m3u8
            """.trimIndent()
        )
        assertEquals("https://cdn.example.com/right.png", channels.single().logo)
    }

    @Test
    fun `an EXTIMG line carries the logo, without overriding an attribute`() {
        val channels = M3uParser.parse(
            """
            #EXTM3U
            #EXTINF:-1,Channel F
            #EXTIMG:https://cdn.example.com/f.png
            https://stream.example.com/f.m3u8
            #EXTINF:-1 tvg-logo="https://cdn.example.com/attr.png",Channel G
            #EXTIMG:https://cdn.example.com/line.png
            https://stream.example.com/g.m3u8
            """.trimIndent()
        )
        assertEquals(
            listOf("https://cdn.example.com/f.png", "https://cdn.example.com/attr.png"),
            channels.map { it.logo }
        )
    }

    @Test
    fun `an absolute address is left alone`() {
        assertEquals(
            "http://cdn.example.com/a.png",
            IptvLogos.resolve(playlist, "http://cdn.example.com/a.png")
        )
    }

    @Test
    fun `a protocol-relative address takes https`() {
        assertEquals(
            "https://cdn.example.com/a.png",
            IptvLogos.resolve(playlist, "//cdn.example.com/a.png")
        )
    }

    @Test
    fun `a host-relative path resolves against the playlist host`() {
        assertEquals(
            "https://lists.example.com/logos/a.png",
            IptvLogos.resolve(playlist, "/logos/a.png")
        )
    }

    @Test
    fun `a path relative to the playlist resolves against its directory`() {
        assertEquals(
            "https://lists.example.com/iptv/playlists/logos/a.png",
            IptvLogos.resolve(playlist, "logos/a.png")
        )
    }

    @Test
    fun `a query on the playlist address is not part of its directory`() {
        assertEquals(
            "http://portal.tv/logos/a.png",
            IptvLogos.resolve("http://portal.tv/get.php?username=u&password=p", "logos/a.png")
        )
    }

    @Test
    fun `a scheme the loader cannot fetch is dropped`() {
        assertEquals("", IptvLogos.resolve(playlist, "data:image/png;base64,iVBORw0KGgo="))
        assertEquals("", IptvLogos.resolve(playlist, "file:///sdcard/a.png"))
        assertEquals("", IptvLogos.resolve(playlist, "ftp://files.example.com/a.png"))
    }

    @Test
    fun `the values a generator writes to mean no logo are dropped`() {
        assertEquals("", IptvLogos.resolve(playlist, ""))
        assertEquals("", IptvLogos.resolve(playlist, "   "))
        assertEquals("", IptvLogos.resolve(playlist, "null"))
        assertEquals("", IptvLogos.resolve(playlist, "N/A"))
    }

    @Test
    fun `a relative logo is dropped when the base says nothing`() {
        assertEquals("", IptvLogos.resolve("", "logos/a.png"))
        assertEquals("", IptvLogos.resolve("not-a-url", "/logos/a.png"))
    }
}
