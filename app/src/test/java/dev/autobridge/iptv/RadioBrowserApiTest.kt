package dev.autobridge.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** radio-browser search: the URL that is sent and the reading of what comes back. */
class RadioBrowserApiTest {
    @Test fun `frequency query is sent without the band word over https`() {
        val url = RadioBrowserApi.searchUrl("FM 95.5")!!
        assertTrue(url.startsWith("https://de1.api.radio-browser.info/json/stations/search?"))
        assertTrue(url.contains("name=95.5&"))
        assertTrue(url.contains("limit=50"))
    }

    @Test fun `thai query is percent-encoded`() {
        val url = RadioBrowserApi.searchUrl("ลูกทุ่ง")!!
        assertTrue(url.contains("name=%E0%B8"))
        assertFalse(url.any { it.code > 127 })
        assertFalse(url.contains(' '))
    }

    @Test fun `spaces are encoded`() {
        val url = RadioBrowserApi.searchUrl("cool 93")!!
        assertTrue(url.contains("name=cool+93") || url.contains("name=cool%2093"))
    }

    @Test fun `blank query has no url`() {
        assertNull(RadioBrowserApi.searchUrl("   "))
    }

    @Test fun `limit is clamped`() {
        assertTrue(RadioBrowserApi.searchUrl("cool", limit = 500)!!.contains("limit=100&"))
        assertTrue(RadioBrowserApi.searchUrl("cool", limit = 0)!!.contains("limit=1&"))
    }

    @Test fun `parse keeps playable stations once`() {
        val json = """
            [
              {"stationuuid":"a","name":"95.5 Delight","url":"http://a.example/raw","url_resolved":"",
               "favicon":"https://a.example/i.png","country":"Thailand","countrycode":"TH",
               "codec":"MP3","bitrate":128,"lastcheckok":1},
              {"stationuuid":"b","name":"Dead FM","url":"http://b.example/s","url_resolved":"http://b.example/s",
               "lastcheckok":0},
              {"stationuuid":"c","name":"Delight again","url":"http://a.example/raw","url_resolved":"",
               "lastcheckok":1},
              {"stationuuid":"d","name":"  Cool 93  ","url":"x","url_resolved":"https://d.example/s",
               "lastcheckok":1}
            ]
        """.trimIndent()
        val entries = RadioBrowserApi.parse(json)
        assertEquals(listOf("95.5 Delight", "Cool 93"), entries.map { it.title })
        val first = entries[0]
        assertEquals("rb:a", first.id)
        assertEquals("http://a.example/raw", first.url)
        assertEquals("Thailand • MP3 128 kbps", first.subtitle)
        assertEquals("TH", first.country)
        assertEquals(IptvEntryType.LIVE, first.type)
        assertEquals("https://d.example/s", entries[1].url)
    }

    @Test fun `garbage reads as no stations`() {
        assertTrue(RadioBrowserApi.parse("{}").isEmpty())
        assertTrue(RadioBrowserApi.parse("not json").isEmpty())
    }

    @Test fun `in-country url is bycountrycodeexact with vote order and hidebroken`() {
        val url = RadioBrowserApi.byCountryUrl("TH")
        assertTrue(url.startsWith("https://de1.api.radio-browser.info/json/stations/bycountrycodeexact/TH?"))
        assertTrue(url.contains("order=votes"))
        assertTrue(url.contains("reverse=true"))
        assertTrue(url.contains("hidebroken=true"))
        assertTrue(url.contains("limit=100"))
    }

    @Test fun `in-country limit is clamped`() {
        assertTrue(RadioBrowserApi.byCountryUrl("TH", limit = 500).contains("limit=100"))
        assertTrue(RadioBrowserApi.byCountryUrl("TH", limit = 0).contains("limit=1"))
    }

    @Test fun `top vote url is path-style with hidebroken and no order or reverse`() {
        val url = RadioBrowserApi.topVoteUrl()
        assertTrue(url.startsWith("https://de1.api.radio-browser.info/json/stations/topvote/100?"))
        assertTrue(url.contains("hidebroken=true"))
        assertFalse(url.contains("order="))
        assertFalse(url.contains("reverse="))
    }

    @Test fun `top vote limit is clamped into the path`() {
        assertTrue(RadioBrowserApi.topVoteUrl(limit = 500).contains("/topvote/100?"))
        assertTrue(RadioBrowserApi.topVoteUrl(limit = 0).contains("/topvote/1?"))
    }

    @Test fun `by state url is bystateexact with vote order and encodes spaces`() {
        val url = RadioBrowserApi.byStateUrl("Chiang Mai")!!
        assertTrue(url.startsWith("https://de1.api.radio-browser.info/json/stations/bystateexact/"))
        assertTrue(url.contains("Chiang+Mai") || url.contains("Chiang%20Mai"))
        assertTrue(url.contains("order=votes"))
        assertTrue(url.contains("reverse=true"))
        assertTrue(url.contains("hidebroken=true"))
    }

    @Test fun `by state url percent-encodes a thai state`() {
        val url = RadioBrowserApi.byStateUrl("กรุงเทพ")!!
        assertTrue(url.contains("/bystateexact/%E0%B8"))
        assertFalse(url.any { it.code > 127 })
        assertFalse(url.contains(' '))
    }

    @Test fun `blank state has no url`() {
        assertNull(RadioBrowserApi.byStateUrl(""))
        assertNull(RadioBrowserApi.byStateUrl("   "))
    }

    @Test fun `by tag url is bytagexact with vote order and encoding`() {
        val url = RadioBrowserApi.byTagUrl("talk")!!
        assertTrue(url.startsWith("https://de1.api.radio-browser.info/json/stations/bytagexact/talk?"))
        assertTrue(url.contains("order=votes"))
        assertTrue(url.contains("reverse=true"))
        assertTrue(url.contains("hidebroken=true"))
    }

    @Test fun `blank tag has no url`() {
        assertNull(RadioBrowserApi.byTagUrl(""))
        assertNull(RadioBrowserApi.byTagUrl("   "))
    }
}
