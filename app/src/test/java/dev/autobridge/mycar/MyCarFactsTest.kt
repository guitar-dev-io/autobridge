package dev.autobridge.mycar

import dev.autobridge.expense.MonthCost
import dev.autobridge.fuel.FuelSummary
import dev.autobridge.maintenance.DueState
import dev.autobridge.maintenance.MaintenanceItem
import dev.autobridge.maintenance.MaintenanceStatus
import dev.autobridge.mycar.MyCarFacts.Fuel
import dev.autobridge.mycar.MyCarFacts.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.YearMonth

class MyCarFactsTest {
    private fun item(km: Int = 10_000, months: Int = 12) = MaintenanceItem(1, "oil", km, months, 0.0, 0L)

    private fun status(state: DueState, kmLeft: Double? = null, daysLeft: Long? = null, item: MaintenanceItem = item()) =
        MaintenanceStatus(item, state, kmLeft, daysLeft)

    private fun summary(fillUps: Int, average: Double?) =
        FuelSummary(fillUps, 0.0, 0.0, average, null, null, 0.0, null)

    @Test fun anEmptyLogHasNoFuelFigure() {
        assertSame(Fuel.Empty, MyCarFacts.fuel(summary(0, null)))
    }

    @Test fun oneFillUpIsLoggedButHasNoAverage() {
        assertEquals(Fuel.Logged(1), MyCarFacts.fuel(summary(1, null)))
    }

    @Test fun theAverageAppearsOnlyOnceTheLogHasOne() {
        assertEquals(Fuel.Average(14.2, 3), MyCarFacts.fuel(summary(3, 14.2)))
    }

    @Test fun anEmptyMonthIsNotZeroBaht() {
        assertNull(MyCarFacts.monthTotal(MonthCost(YearMonth.of(2026, 10), 0.0, 0.0)))
        assertEquals(3_450.0, MyCarFacts.monthTotal(MonthCost(YearMonth.of(2026, 10), 2_480.0, 970.0))!!, 0.001)
    }

    @Test fun theHeadlineSkipsAnItemWithNothingToMeasure() {
        val unknown = status(DueState.OK)
        val oil = status(DueState.SOON, kmLeft = 800.0)
        assertSame(oil, MyCarFacts.maintenanceHeadline(listOf(unknown, oil)))
        assertSame(unknown, MyCarFacts.maintenanceHeadline(listOf(unknown)))
        assertNull(MyCarFacts.maintenanceHeadline(emptyList()))
    }

    @Test fun onlyAMeasuredItemCanWarn() {
        assertEquals(Tone.EMPTY, MyCarFacts.maintenanceTone(null))
        assertEquals(Tone.NORMAL, MyCarFacts.maintenanceTone(status(DueState.OVERDUE)))
        assertEquals(Tone.WARN, MyCarFacts.maintenanceTone(status(DueState.SOON, kmLeft = 200.0)))
        assertEquals(Tone.ALERT, MyCarFacts.maintenanceTone(status(DueState.OVERDUE, daysLeft = -3)))
    }

    @Test fun theBarFollowsWhicheverIntervalIsFurtherAlong() {
        // 800 of 10,000 km left is 92% used; 300 of ~365 days left is ~18%.
        assertEquals(0.92f, MyCarFacts.intervalUsed(status(DueState.SOON, kmLeft = 800.0, daysLeft = 300))!!, 0.001f)
    }

    @Test fun anOverdueBarIsFullAndAnUnmeasuredOneIsAbsent() {
        assertEquals(1f, MyCarFacts.intervalUsed(status(DueState.OVERDUE, kmLeft = -500.0))!!, 0f)
        assertNull(MyCarFacts.intervalUsed(status(DueState.OK)))
        // A date given outright with no repeat interval: past it is full, before it there is no scale.
        val oneOff = item(km = 0, months = 0)
        assertEquals(1f, MyCarFacts.intervalUsed(status(DueState.OVERDUE, daysLeft = -2, item = oneOff))!!, 0f)
        assertNull(MyCarFacts.intervalUsed(status(DueState.OK, daysLeft = 20, item = oneOff)))
    }
}
