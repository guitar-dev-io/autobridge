package dev.autobridge.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class IptvEpgTest {
    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

    private fun listing(title: String, start: Long, stop: Long) =
        """{"title":"$title","start_timestamp":"$start","stop_timestamp":"$stop"}"""

    @Test fun picksTheProgrammeOnNow() {
        val json = """{"epg_listings":[${listing(b64("News"), 100, 200)},${listing(b64("Drama"), 200, 300)}]}"""
        assertEquals("Drama", IptvEpg.parseNow(json, nowSec = 250))
    }

    @Test fun fallsBackToTheFirstWhenClocksDisagree() {
        val json = """{"epg_listings":[${listing(b64("News"), 100, 200)}]}"""
        assertEquals("News", IptvEpg.parseNow(json, nowSec = 9_999))
    }

    @Test fun readsThaiTitles() {
        val json = """{"epg_listings":[${listing(b64("ข่าวเย็น"), 0, 10)}]}"""
        assertEquals("ข่าวเย็น", IptvEpg.parseNow(json, nowSec = 5))
    }

    @Test fun keepsPlainTitles() {
        val json = """{"epg_listings":[${listing("Live Football", 0, 10)}]}"""
        assertEquals("Live Football", IptvEpg.parseNow(json, nowSec = 5))
    }

    @Test fun noGuideIsNull() {
        assertNull(IptvEpg.parseNow("""{"epg_listings":[]}"""))
        assertNull(IptvEpg.parseNow("[]"))
    }
}
