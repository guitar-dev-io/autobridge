package dev.autobridge.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripStatsTest {
    private fun trip(start: Double? = 10_000.0, end: Double? = 10_600.0, people: Int = 5, other: Double = 0.0, perKm: Double? = 2.0) =
        Trip(1, 1_000, 5_000, start, end, people, other, perKm)

    @Test fun costIsSharedBetweenEveryone() {
        // 600 km at 2 baht/km = 1,200, plus 300 of tolls, over 5 people.
        val t = trip(other = 300.0)
        assertEquals(600.0, TripStats.distanceKm(t)!!, 0.001)
        assertEquals(1_500.0, TripStats.totalBaht(t)!!, 0.001)
        assertEquals(300.0, TripStats.perPersonBaht(t)!!, 0.001)
    }

    @Test fun withoutACostPerKmOnlyTheExtrasCount() {
        val t = trip(perKm = null, other = 250.0, people = 2)
        assertNull(TripStats.energyBaht(t))
        assertEquals(125.0, TripStats.perPersonBaht(t)!!, 0.001)
    }

    @Test fun nothingToShareIsNull() {
        assertNull(TripStats.totalBaht(trip(perKm = null)))
        assertNull(TripStats.perPersonBaht(trip(start = null)))
    }

    @Test fun anEndBehindTheStartIsNoDistance() {
        assertNull(TripStats.distanceKm(trip(end = 9_000.0)))
    }

    @Test fun aTripInProgressUsesTheCurrentReading() {
        val going = trip(end = null).copy(endMs = 0)
        assertTrue(going.active)
        assertEquals(250.0, TripStats.distanceKm(going, currentKm = 10_250.0)!!, 0.001)
        assertNull(TripStats.distanceKm(going))
    }

    @Test fun peopleNeverDividesByZero() {
        assertEquals(1_200.0, TripStats.perPersonBaht(trip(people = 0))!!, 0.001)
    }

    @Test fun durationOfAnActiveTripRunsToNow() {
        assertEquals(9_000L, TripStats.durationMs(trip().copy(endMs = 0), nowMs = 10_000))
        assertEquals(4_000L, TripStats.durationMs(trip(), nowMs = 99_000))
    }
}
