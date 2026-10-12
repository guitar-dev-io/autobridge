package dev.autobridge.parking

import dev.autobridge.parking.ParkingFixChoice.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkingFixChoiceTest {
    private val now = 10_000_000L
    private fun at(accuracy: Float?, ageMs: Long = 0, lat: Double = 13.75) = Candidate(lat, 100.5, accuracy, now - ageMs)

    @Test fun aPreciseRecentReadingEndsTheWait() {
        assertTrue(ParkingFixChoice.isGood(at(12f), now))
        assertTrue(ParkingFixChoice.isGood(at(20f, ageMs = 60_000), now))
    }

    @Test fun aVagueReadingDoesNotEndTheWaitButCanBeKept() {
        assertFalse(ParkingFixChoice.isGood(at(90f), now))
        assertTrue(ParkingFixChoice.isUsable(at(90f), now))
    }

    @Test fun oldVagueOrMissingAccuracyReadingsAreNeverKept() {
        assertFalse(ParkingFixChoice.isUsable(at(10f, ageMs = 10 * 60_000), now))
        assertFalse(ParkingFixChoice.isUsable(at(800f), now))
        assertFalse(ParkingFixChoice.isUsable(at(null), now))
        assertFalse(ParkingFixChoice.isUsable(at(5f, lat = 0.0).copy(lon = 0.0), now))
    }

    @Test fun theMostPreciseUsableReadingWins() {
        val wifi = at(60f)
        val gps = at(25f, ageMs = 30_000)
        assertEquals(gps, ParkingFixChoice.best(listOf(wifi, gps, at(900f)), now))
        assertNull(ParkingFixChoice.best(listOf(at(900f), at(10f, ageMs = 3_600_000)), now))
    }
}
