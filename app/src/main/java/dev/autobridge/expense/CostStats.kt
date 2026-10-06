package dev.autobridge.expense

import dev.autobridge.fuel.FuelEntry
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Money spent on the car outside the fuel log: a service, a wash, tolls, parking, a tax. */
data class Expense(val id: Long, val timeMs: Long, val label: String, val baht: Double)

/** One month's spending, split between fuel (or charging) and everything else. */
data class MonthCost(val month: YearMonth, val fuelBaht: Double, val otherBaht: Double) {
    val totalBaht: Double get() = fuelBaht + otherBaht
}

/** The monthly sums, pure so they are tested on the JVM. */
object CostStats {
    /**
     * Spending per month, newest first, for every month that has any. The fuel or charging log and
     * the other expenses are summed by the month of their date, so a fill-up entered late still
     * lands in its own month.
     */
    fun monthly(fuel: List<FuelEntry>, expenses: List<Expense>, zone: ZoneId = ZoneId.systemDefault()): List<MonthCost> {
        fun month(timeMs: Long) = YearMonth.from(Instant.ofEpochMilli(timeMs).atZone(zone))
        val fuelBy = fuel.groupBy { month(it.timeMs) }.mapValues { (_, list) -> list.sumOf { it.totalBaht } }
        val otherBy = expenses.groupBy { month(it.timeMs) }.mapValues { (_, list) -> list.sumOf { it.baht } }
        return (fuelBy.keys + otherBy.keys).sortedDescending().map {
            MonthCost(it, fuelBy[it] ?: 0.0, otherBy[it] ?: 0.0)
        }
    }

    /** The month containing [nowMs], with zeros when nothing was spent yet. */
    fun thisMonth(costs: List<MonthCost>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): MonthCost {
        val now = YearMonth.from(Instant.ofEpochMilli(nowMs).atZone(zone))
        return costs.firstOrNull { it.month == now } ?: MonthCost(now, 0.0, 0.0)
    }

    /**
     * The average of the up to [months] full months before the current one that have any
     * spending, or null when there are none. The current month is left out: it is not finished.
     */
    fun average(costs: List<MonthCost>, nowMs: Long, months: Int = 3, zone: ZoneId = ZoneId.systemDefault()): MonthCost? {
        val now = YearMonth.from(Instant.ofEpochMilli(nowMs).atZone(zone))
        val past = costs.filter { it.month < now }.take(months)
        if (past.isEmpty()) return null
        return MonthCost(now, past.sumOf { it.fuelBaht } / past.size, past.sumOf { it.otherBaht } / past.size)
    }
}
