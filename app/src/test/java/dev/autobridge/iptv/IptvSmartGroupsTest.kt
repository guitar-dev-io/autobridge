package dev.autobridge.iptv

import dev.autobridge.iptv.IptvSmartGroups.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Smart groups and the broken-channel record, both pure. */
class IptvSmartGroupsTest {
    private fun live(title: String, group: String = "", categoryId: String = "x") = IptvEntry(
        id = "m3u:$title", title = title, categoryId = categoryId, type = IptvEntryType.LIVE,
        url = "http://example.com/${title.hashCode()}", subtitle = group
    )

    @Test fun `channel names file by what they show`() {
        assertEquals(Kind.NEWS, IptvSmartGroups.classify(live("BBC News")))
        assertEquals(Kind.SPORTS, IptvSmartGroups.classify(live("beIN Sports 1 HD")))
        assertEquals(Kind.KIDS, IptvSmartGroups.classify(live("Cartoon Network")))
        assertEquals(Kind.DOCUMENTARY, IptvSmartGroups.classify(live("Nat Geo Wild")))
        assertEquals(Kind.MUSIC, IptvSmartGroups.classify(live("MTV Hits")))
    }

    @Test fun `thai names file without spaces between words`() {
        assertEquals(Kind.NEWS, IptvSmartGroups.classify(live("ไทยรัฐข่าว")))
        assertEquals(Kind.SPORTS, IptvSmartGroups.classify(live("ช่องกีฬาสด")))
        assertEquals(Kind.KIDS, IptvSmartGroups.classify(live("การ์ตูนคลับ")))
    }

    @Test fun `latin keywords match only at the start of a word`() {
        // "art" in "Smart" or "cine" inside a word must not drag a channel into a group.
        assertNull(IptvSmartGroups.classify(live("Smart Channel")))
        assertNull(IptvSmartGroups.classify(live("Medicine Today")))
        assertEquals(Kind.NEWS, IptvSmartGroups.classify(live("Newsmax")))
    }

    @Test fun `the provider group is read when the name says nothing`() {
        assertEquals(Kind.SPORTS, IptvSmartGroups.classify(live("Channel 5", group = "TH | Sports")))
        assertEquals(Kind.MOVIES, IptvSmartGroups.classify(live("Channel 9"), categoryName = "VIP Movies"))
        assertNull(IptvSmartGroups.classify(live("Channel 9", group = "Thailand")))
    }

    @Test fun `groups need two channels and keep catalog order`() {
        val entries = listOf(live("CNN"), live("Sky News"), live("ESPN"), live("Plain"))
        val groups = IptvSmartGroups.group(entries)
        assertEquals(listOf(Kind.NEWS), groups.map { it.kind })
        assertEquals(listOf("CNN", "Sky News"), groups.single().entries.map { it.title })
    }

    @Test fun `movies and series folders are not live channels`() {
        val movie = live("Movie Night").copy(type = IptvEntryType.MOVIE)
        assertTrue(IptvSmartGroups.group(listOf(movie, movie.copy(id = "2"))).isEmpty())
    }

    @Test fun `xtream channels are grouped by their category name`() {
        val entries = listOf(live("Ch 1", categoryId = "7"), live("Ch 2", categoryId = "7"))
        val groups = IptvSmartGroups.group(entries, mapOf("7" to "TH | ข่าว"))
        assertEquals(Kind.NEWS, groups.single().kind)
    }

    // ----- broken-channel record -----

    @Test fun `a health record survives its own encoding`() {
        val record = IptvHealthStore.Record(lastFailMs = 200, lastOkMs = 100, failures = 2, reason = "HTTP 403")
        assertEquals(record, IptvHealthStore.Record.decode(record.encode()))
        assertTrue(record.isBroken)
        assertNull(IptvHealthStore.Record.decode("garbage"))
    }

    @Test fun `a channel that played after failing is not broken`() {
        assertTrue(!IptvHealthStore.Record(lastFailMs = 100, lastOkMs = 200, failures = 0, reason = "").isBroken)
    }
}
