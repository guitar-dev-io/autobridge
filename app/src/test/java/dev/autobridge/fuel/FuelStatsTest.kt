package dev.autobridge.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class FuelStatsTest {
    private val zone = ZoneOffset.UTC
    private fun at(day: Int, month: Int = 10) =
        LocalDateTime.of(2026, month, day, 9, 0).toInstant(zone).toEpochMilli()

    private fun fill(id: Long, day: Int, liters: Double, baht: Double, odo: Double?, month: Int = 10) =
        FuelEntry(id, at(day, month), liters, baht, odo)

    @Test fun fullToFullConsumption() {
        val log = listOf(
            fill(1, 1, 40.0, 1400.0, 10_000.0),
            fill(2, 8, 35.0, 1225.0, 10_490.0), // 490 km on 35 L = 14.0
            fill(3, 15, 30.0, 1080.0, 10_910.0), // 420 km on 30 L = 14.0
        )
        val summary = FuelStats.summarize(log, nowMs = at(20), zone = zone)
        assertEquals(14.0, summary.averageKmPerLiter!!, 0.001)
        assertEquals(14.0, summary.lastKmPerLiter!!, 0.001)
        assertEquals((1225.0 + 1080.0) / 910.0, summary.bahtPerKm!!, 0.0001)
        assertEquals(3, summary.fillUps)
        assertEquals(3705.0, summary.thisMonthBaht, 0.001)
    }

    @Test fun theFirstFillUpOnlyStartsTheCount() {
        val summary = FuelStats.summarize(listOf(fill(1, 1, 40.0, 1400.0, 10_000.0)), at(2), zone)
        assertNull(summary.averageKmPerLiter)
        assertNull(summary.bahtPerKm)
        assertEquals(1400.0, summary.totalBaht, 0.001)
    }

    @Test fun aFillUpWithoutAReadingIsCarriedIntoTheNextInterval() {
        val log = listOf(
            fill(1, 1, 40.0, 1400.0, 10_000.0),
            fill(2, 5, 20.0, 700.0, null),
            fill(3, 8, 15.0, 525.0, 10_490.0),
        )
        val interval = FuelStats.intervals(log).single()
        assertEquals(490.0 / 35.0, interval.kmPerLiter, 0.001)
        assertEquals(1225.0, interval.baht, 0.001)
    }

    @Test fun aBackwardsReadingIsLeftOut() {
        val log = listOf(
            fill(1, 1, 40.0, 1400.0, 10_000.0),
            fill(2, 8, 35.0, 1225.0, 10_490.0),
            fill(3, 9, 10.0, 350.0, 10_400.0), // typo: runs backwards
            fill(4, 15, 30.0, 1080.0, 10_910.0),
        )
        assertEquals(listOf(2L, 4L), FuelStats.intervals(log).map { it.entry.id })
    }

    @Test fun thisMonthCountsOnlyThisMonth() {
        val log = listOf(fill(1, 28, 40.0, 1400.0, 1.0, month = 9), fill(2, 3, 30.0, 1000.0, 2.0))
        assertEquals(1000.0, FuelStats.summarize(log, at(10), zone).thisMonthBaht, 0.001)
    }

    @Test fun csvHasAHeaderAndOneLinePerFillUp() {
        val log = listOf(fill(1, 1, 40.0, 1400.0, 10_000.0), fill(2, 8, 35.0, 1225.0, 10_490.0))
        val lines = FuelStats.csv(log, zone).trim().lines()
        assertEquals(3, lines.size)
        assertTrue(lines[2].contains("14.00"))
        assertTrue(lines[2].startsWith("2026-10-08 09:00"))
    }

    @Test fun anEvLogHasEnergyColumnsButTheSameNumbers() {
        val log = listOf(fill(1, 1, 40.0, 1400.0, 10_000.0), fill(2, 8, 35.0, 1225.0, 10_490.0))
        val lines = FuelStats.csv(log, zone, ev = true).trim().lines()
        assertTrue(lines[0].startsWith("date,kwh,") && lines[0].contains("km_per_kwh"))
        assertEquals(FuelStats.csv(log, zone).trim().lines()[2], lines[2])
    }
}
