package dev.autobridge.expense

import dev.autobridge.fuel.FuelEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset

class CostStatsTest {
    private val zone = ZoneOffset.UTC
    private fun at(month: Int, day: Int, year: Int = 2026) =
        LocalDateTime.of(year, month, day, 9, 0).toInstant(zone).toEpochMilli()

    private fun fill(id: Long, month: Int, baht: Double) = FuelEntry(id, at(month, 5), 30.0, baht, null)
    private fun spend(id: Long, month: Int, baht: Double) = Expense(id, at(month, 10), "x", baht)

    @Test fun monthsAreSummedSeparatelyAndNewestFirst() {
        val costs = CostStats.monthly(
            fuel = listOf(fill(1, 8, 1400.0), fill(2, 8, 1200.0), fill(3, 9, 1500.0)),
            expenses = listOf(spend(1, 9, 300.0), spend(2, 7, 4000.0)),
            zone = zone,
        )
        assertEquals(listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 8), YearMonth.of(2026, 7)), costs.map { it.month })
        assertEquals(1500.0, costs[0].fuelBaht, 0.001)
        assertEquals(300.0, costs[0].otherBaht, 0.001)
        assertEquals(2600.0, costs[1].fuelBaht, 0.001)
        assertEquals(4000.0, costs[2].totalBaht, 0.001)
    }

    @Test fun aMonthWithNothingIsLeftOut() {
        assertEquals(listOf(YearMonth.of(2026, 3)), CostStats.monthly(listOf(fill(1, 3, 100.0)), emptyList(), zone).map { it.month })
    }

    @Test fun thisMonthIsZeroUntilSomethingIsSpent() {
        val costs = CostStats.monthly(listOf(fill(1, 8, 1400.0)), emptyList(), zone)
        assertEquals(0.0, CostStats.thisMonth(costs, at(10, 3), zone).totalBaht, 0.001)
        assertEquals(1400.0, CostStats.thisMonth(costs, at(8, 20), zone).totalBaht, 0.001)
    }

    @Test fun theAverageLeavesOutTheMonthInProgress() {
        val costs = CostStats.monthly(
            fuel = listOf(fill(1, 7, 1000.0), fill(2, 8, 2000.0), fill(3, 9, 3000.0), fill(4, 10, 9999.0)),
            expenses = listOf(spend(1, 8, 600.0)),
            zone = zone,
        )
        val average = CostStats.average(costs, at(10, 15), months = 3, zone = zone)!!
        assertEquals(2000.0, average.fuelBaht, 0.001) // (1000 + 2000 + 3000) / 3
        assertEquals(200.0, average.otherBaht, 0.001) // 600 / 3
    }

    @Test fun noFinishedMonthMeansNoAverage() {
        val costs = CostStats.monthly(listOf(fill(1, 10, 500.0)), emptyList(), zone)
        assertNull(CostStats.average(costs, at(10, 15), zone = zone))
    }
}
