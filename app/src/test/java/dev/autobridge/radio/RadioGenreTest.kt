package dev.autobridge.radio

import dev.autobridge.iptv.IptvCatalogData
import dev.autobridge.iptv.IptvEntry
import dev.autobridge.iptv.IptvEntryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioGenreTest {
    private fun station(title: String, tags: String = "") = IptvEntry(
        id = title, title = title, categoryId = IptvCatalogData.ALL_CATEGORY_ID,
        type = IptvEntryType.LIVE, url = "https://example.invalid/$title", tags = tags
    )

    @Test fun matchesOnTagsAndOnTheName() {
        assertTrue(RadioGenre.LUK_THUNG.matches(station("FM 95 ลูกทุ่งมหานคร")))
        assertTrue(RadioGenre.LUK_THUNG.matches(station("Station X", tags = "Luk Thung,thai")))
        assertTrue(RadioGenre.NEWS.matches(station("Radio Thailand", tags = "news,talk")))
    }

    @Test fun onlyGenresWithAStationAreOffered() {
        val entries = listOf(station("Pop Hits 88", tags = "pop"), station("ข่าว 97"))
        assertEquals(listOf(RadioGenre.ALL, RadioGenre.POP, RadioGenre.NEWS), RadioGenre.available(entries))
    }

    @Test fun allKeepsEveryStation() {
        val entries = listOf(station("A"), station("B", tags = "rock"))
        assertEquals(entries, RadioGenre.filter(entries, RadioGenre.ALL))
        assertEquals(listOf(entries[1]), RadioGenre.filter(entries, RadioGenre.INTERNATIONAL))
    }
}
