package dev.autobridge.maintenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class MaintenanceStatsTest {
    private val zone = ZoneOffset.UTC
    private fun at(month: Int, day: Int, year: Int = 2026) =
        LocalDateTime.of(year, month, day, 9, 0).toInstant(zone).toEpochMilli()

    private fun item(km: Int, months: Int, lastKm: Double? = 10_000.0, last: Long = at(1, 1)) =
        MaintenanceItem(1, "oil", km, months, lastKm, last)

    @Test fun distanceCountsDownFromTheLastService() {
        val s = MaintenanceStats.status(item(10_000, 0), 14_000.0, at(2, 1), zone)
        assertEquals(6_000.0, s.kmLeft!!, 0.001)
        assertNull(s.daysLeft)
        assertEquals(DueState.OK, s.state)
    }

    @Test fun pastTheDistanceIsOverdue() {
        assertEquals(DueState.OVERDUE, MaintenanceStats.status(item(10_000, 0), 20_500.0, at(2, 1), zone).state)
    }

    @Test fun theLastTenthOfTheDistanceIsSoon() {
        assertEquals(DueState.SOON, MaintenanceStats.status(item(10_000, 0), 19_100.0, at(2, 1), zone).state)
        assertEquals(DueState.OK, MaintenanceStats.status(item(10_000, 0), 18_900.0, at(2, 1), zone).state)
    }

    @Test fun aShortIntervalStillGetsTheMinimumWarning() {
        // 1,000 km interval: a tenth is 100 km, but 300 km of notice is the floor.
        assertEquals(DueState.SOON, MaintenanceStats.status(item(1_000, 0), 10_750.0, at(2, 1), zone).state)
    }

    @Test fun dateAloneDrivesAMonthsOnlyItem() {
        val tax = item(0, 12, lastKm = null)
        assertEquals(DueState.OK, MaintenanceStats.status(tax, null, at(6, 1), zone).state)
        assertEquals(DueState.SOON, MaintenanceStats.status(tax, null, at(12, 20), zone).state)
        assertEquals(DueState.OVERDUE, MaintenanceStats.status(tax, null, at(1, 5, 2027), zone).state)
    }

    @Test fun withoutAnOdometerOnlyTheDateCounts() {
        val s = MaintenanceStats.status(item(10_000, 12), null, at(2, 1), zone)
        assertNull(s.kmLeft)
        assertTrue(s.daysLeft!! > 300)
    }

    @Test fun whicheverComesFirstWins() {
        // Far from the distance, past the date.
        assertEquals(DueState.OVERDUE, MaintenanceStats.status(item(10_000, 12), 11_000.0, at(2, 1, 2027), zone).state)
    }

    @Test fun mostUrgentFirst() {
        val ok = item(10_000, 0).copy(id = 1)
        val over = item(10_000, 0, lastKm = 1_000.0).copy(id = 2)
        val soon = item(10_000, 0, lastKm = 1_500.0).copy(id = 3)
        val order = MaintenanceStats.ordered(listOf(ok, soon, over), 11_300.0, at(2, 1), zone).map { it.item.id }
        assertEquals(listOf(2L, 3L, 1L), order)
    }

    @Test fun anEvIsNotOfferedOilOrAnAirFilter() {
        val ev = MaintenanceStats.presets(ev = true).map { it.key }
        assertTrue("engine_oil" !in ev && "air_filter" !in ev)
        assertTrue("battery_coolant" in ev && "cabin_filter" in ev)
        assertTrue("engine_oil" in MaintenanceStats.presets(ev = false).map { it.key })
    }
}
