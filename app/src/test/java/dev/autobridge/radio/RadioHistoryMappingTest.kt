package dev.autobridge.radio

import dev.autobridge.iptv.IptvCatalogData
import dev.autobridge.iptv.IptvEntryType
import dev.autobridge.iptv.IptvHistoryStore
import dev.autobridge.iptv.IptvKind
import dev.autobridge.iptv.IptvPlayback
import dev.autobridge.iptv.IptvSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two-way store mapping: a stored RADIO item reads back as an internet station (no frequency,
 * no round-tripped subtitle), a station synthesises the right (source, entry) pair, and an
 * FM/blank-URL station is never persisted — synthesising it throws before any store is touched.
 */
class RadioHistoryMappingTest {

    private fun item(url: String, title: String = url) = IptvHistoryStore.Item(
        sourceId = "radio-virtual",
        title = title,
        url = url,
        type = IptvEntryType.LIVE,
        kind = IptvKind.RADIO,
        logo = "logo.png",
    )

    @Test fun `a stored item reads back as an internet station`() {
        val station = RadioRepository.RadioHistory.toRadioStation(item("http://a", "Alpha"))

        assertEquals(RadioSourceType.INTERNET_RADIO, station.sourceType)
        assertEquals("http://a", station.id)
        assertEquals("http://a", station.streamUrl)
        assertEquals("Alpha", station.title)
        assertEquals("logo.png", station.logo)
        // Item has no subtitle, so it is not round-tripped, and there is never a frequency.
        assertEquals("", station.subtitle)
        assertNull(station.frequencyKHz)
    }

    @Test fun `synth builds a RADIO M3U source and a LIVE stream entry`() {
        val station = RadioStation(
            sourceType = RadioSourceType.INTERNET_RADIO,
            id = "http://a",
            title = "Alpha",
            streamUrl = "http://a",
            logo = "logo.png",
            subtitle = "Thailand",
        )

        val (source, entry) = RadioRepository.RadioHistory.synthForStore(station)

        assertEquals("radio-virtual", source.id)
        assertEquals(IptvKind.RADIO, source.kind)
        assertEquals(IptvSourceType.M3U, source.type)
        assertEquals("http://a", source.url)

        assertEquals("http://a", entry.id)
        assertEquals("Alpha", entry.title)
        assertEquals(IptvCatalogData.ALL_CATEGORY_ID, entry.categoryId)
        assertEquals(IptvEntryType.LIVE, entry.type)
        assertEquals(IptvPlayback.STREAM, entry.playback)
        assertEquals("http://a", entry.url)
        assertEquals("logo.png", entry.logo)
        assertEquals("Thailand", entry.subtitle)
    }

    @Test fun `synthesising an FM station throws`() {
        val fm = RadioStation(RadioSourceType.HARDWARE_FM, id = "fm:95.5", title = "95.5", streamUrl = "")
        assertThrows { RadioRepository.RadioHistory.synthForStore(fm) }
    }

    @Test fun `synthesising a blank-url station throws`() {
        val blank = RadioStation(RadioSourceType.INTERNET_RADIO, id = "x", title = "x", streamUrl = "")
        assertThrows { RadioRepository.RadioHistory.synthForStore(blank) }
    }

    private fun assertThrows(block: () -> Unit) {
        var threw = false
        try {
            block()
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("expected IllegalArgumentException", threw)
    }
}
