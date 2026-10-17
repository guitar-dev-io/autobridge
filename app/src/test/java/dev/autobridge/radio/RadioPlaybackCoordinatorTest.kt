package dev.autobridge.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The coordinator over a faked transport: switching only flips selected/active (it never loads a
 * queue), the unavailable branch is uniform for FM and offline, and [RadioPlaybackCoordinator.play]
 * is the single call that reaches the session with aligned URIs/titles and a coerced index.
 */
class RadioPlaybackCoordinatorTest {

    private fun station(url: String, title: String = url, type: RadioSourceType = RadioSourceType.INTERNET_RADIO) =
        RadioStation(sourceType = type, id = url, title = title, streamUrl = url)

    @Test fun `switch to an available source flips active and selected without loading a queue`() {
        val client = FakeRadioTransportClient(queueUris = listOf("http://a"))
        val coord = RadioPlaybackCoordinator(client)

        // Start on internet; switching to the same available source stays activated, no play.
        val result = coord.switchSource(FakeRadioContext(), RadioSourceType.INTERNET_RADIO)

        assertEquals(RadioPlaybackCoordinator.SwitchResult.Activated, result)
        assertEquals(RadioSourceType.INTERNET_RADIO, coord.activeSource())
        assertEquals(RadioSourceType.INTERNET_RADIO, coord.selectedSource())
        assertEquals("a bare switch must never load audio", 0, client.playCount)
    }

    @Test fun `switch to FM is unavailable and leaves active source and audio untouched`() {
        val client = FakeRadioTransportClient()
        val coord = RadioPlaybackCoordinator(client)

        val result = coord.switchSource(FakeRadioContext(), RadioSourceType.HARDWARE_FM)

        assertTrue(result is RadioPlaybackCoordinator.SwitchResult.Unavailable)
        assertEquals(RadioSourceType.HARDWARE_FM, coord.selectedSource())
        // Active source and audio are untouched: internet still owns the (empty) session.
        assertEquals(RadioSourceType.INTERNET_RADIO, coord.activeSource())
        assertEquals(0, client.playCount)
    }

    @Test fun `switch to offline takes the same uniform unavailable branch as FM`() {
        val client = FakeRadioTransportClient()
        val coord = RadioPlaybackCoordinator(client)

        val result = coord.switchSource(FakeRadioContext(), RadioSourceType.OFFLINE_AUDIO)

        assertTrue(result is RadioPlaybackCoordinator.SwitchResult.Unavailable)
        assertEquals(RadioSourceType.OFFLINE_AUDIO, coord.selectedSource())
        assertEquals(RadioSourceType.INTERNET_RADIO, coord.activeSource())
        assertEquals(0, client.playCount)
    }

    @Test fun `play maps the queue to parallel uris and titles with the given start index`() {
        val client = FakeRadioTransportClient()
        val coord = RadioPlaybackCoordinator(client)

        val queue = listOf(
            station("http://a", "A"),
            station("http://b", "B"),
            station("http://c", "C"),
        )
        coord.play(queue[1], queue, startIndex = 1)

        val call = requireNotNull(client.lastPlay)
        assertEquals(listOf("http://a", "http://b", "http://c"), call.uris)
        assertEquals(listOf("A", "B", "C"), call.titles)
        assertEquals(1, call.startIndex)
        assertEquals(1, client.playCount)
    }

    @Test fun `play coerces an out-of-range start index into the queue`() {
        val client = FakeRadioTransportClient()
        val coord = RadioPlaybackCoordinator(client)

        val queue = listOf(station("http://a"), station("http://b"))
        coord.play(queue[0], queue, startIndex = 9)

        assertEquals(1, requireNotNull(client.lastPlay).startIndex)
    }

    @Test fun `play on a single-item queue starts at index zero`() {
        val client = FakeRadioTransportClient()
        val coord = RadioPlaybackCoordinator(client)

        val anchor = station("http://only")
        coord.play(anchor, listOf(anchor), startIndex = 0)

        val call = requireNotNull(client.lastPlay)
        assertEquals(listOf("http://only"), call.uris)
        assertEquals(0, call.startIndex)
    }

    @Test fun `play rejects a queue with a blank url`() {
        val coord = RadioPlaybackCoordinator(FakeRadioTransportClient())
        val anchor = station("http://a")
        val badQueue = listOf(anchor, station(""))
        var threw = false
        try {
            coord.play(anchor, badQueue, startIndex = 0)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("a non-playable queue entry must be rejected", threw)
    }

    @Test fun `transport commands delegate to the client`() {
        val client = FakeRadioTransportClient()
        val coord = RadioPlaybackCoordinator(client)
        coord.pause(); coord.resume(); coord.next(); coord.previous(); coord.stop()
        assertEquals(1, client.pauseCount)
        assertEquals(1, client.resumeCount)
        assertEquals(1, client.nextCount)
        assertEquals(1, client.previousCount)
        assertEquals(1, client.stopCount)
    }

    @Test fun `playing a station makes it the active and selected source`() {
        val client = FakeRadioTransportClient()
        val coord = RadioPlaybackCoordinator(client)
        val anchor = station("http://a")
        coord.play(anchor, listOf(anchor), startIndex = 0)
        assertEquals(RadioSourceType.INTERNET_RADIO, coord.activeSource())
        assertEquals(RadioSourceType.INTERNET_RADIO, coord.selectedSource())
    }
}
