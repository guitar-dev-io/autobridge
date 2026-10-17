package dev.autobridge.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioNowPlayingTest {
    @Test fun aStreamThatNamesNothingShowsTheStation() {
        val now = RadioNowPlaying.of("Cool 93", "Cool 93", null)
        assertNull(now.song)
        assertNull(now.artist)
        assertEquals("Cool 93", now.station)
    }

    @Test fun anIcyTitleIsSplitIntoArtistAndSong() {
        val now = RadioNowPlaying.of("Cool 93", "Wanyai & Mon Monik - Kham Wa Rak", null)
        assertEquals("Kham Wa Rak", now.song)
        assertEquals("Wanyai & Mon Monik", now.artist)
        assertEquals("Cool 93", now.station)
    }

    @Test fun aTitleWithNoSeparatorIsTheSongWhole() {
        val now = RadioNowPlaying.of("News 97", "Morning bulletin", "Host")
        assertEquals("Morning bulletin", now.song)
        assertEquals("Host", now.artist)
    }

    @Test fun theStationNameIsComparedIgnoringCase() {
        assertNull(RadioNowPlaying.of("EFM 94", "efm 94", null).song)
    }

    @Test fun noQueueNameFallsBackToTheSessionTitleAsTheStation() {
        val now = RadioNowPlaying.of(null, "Some Station", null)
        assertNull(now.song)
        assertEquals("Some Station", now.station)
    }

    @Test fun aSeparatorAtTheEdgeDoesNotSplit() {
        assertEquals("- Intro", RadioNowPlaying.of("A", "- Intro", null).song)
    }
}
