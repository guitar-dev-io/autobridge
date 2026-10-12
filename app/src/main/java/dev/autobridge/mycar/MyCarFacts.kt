package dev.autobridge.mycar

import dev.autobridge.expense.MonthCost
import dev.autobridge.fuel.FuelSummary
import dev.autobridge.maintenance.DueState
import dev.autobridge.maintenance.MaintenanceStatus

/**
 * What each My Car tile says, chosen from the data the tools already keep. Pure, so the rule that a
 * tile never shows a figure the data does not support is tested on the JVM: no average before two
 * readings, no zero baht for an empty month, no "due" for an item with nothing to measure against.
 */
object MyCarFacts {

    /** How a tile is coloured. Only a real due date or distance may make it [WARN] or [ALERT]. */
    enum class Tone { NORMAL, WARN, ALERT, EMPTY }

    sealed interface Fuel {
        /** Average consumption across the measurable intervals; [fillUps] counts every entry. */
        data class Average(val kmPerUnit: Double, val fillUps: Int) : Fuel

        /** Entries exist, but no two with an odometer yet, so there is no average to show. */
        data class Logged(val fillUps: Int) : Fuel

        data object Empty : Fuel
    }

    /** The item to name on the tile: the most urgent one that can be measured, else the first. */
    fun maintenanceHeadline(ordered: List<MaintenanceStatus>): MaintenanceStatus? =
        ordered.firstOrNull { it.kmLeft != null || it.daysLeft != null } ?: ordered.firstOrNull()

    fun maintenanceTone(status: MaintenanceStatus?): Tone = when {
        status == null -> Tone.EMPTY
        status.kmLeft == null && status.daysLeft == null -> Tone.NORMAL
        status.state == DueState.OVERDUE -> Tone.ALERT
        status.state == DueState.SOON -> Tone.WARN
        else -> Tone.NORMAL
    }

    fun fuel(summary: FuelSummary): Fuel = when {
        summary.fillUps == 0 -> Fuel.Empty
        summary.averageKmPerLiter != null -> Fuel.Average(summary.averageKmPerLiter, summary.fillUps)
        else -> Fuel.Logged(summary.fillUps)
    }

    /** This month's total, or null when nothing was spent yet (the tile then says so in words). */
    fun monthTotal(month: MonthCost): Double? = month.totalBaht.takeIf { it > 0.0 }

    /**
     * How much of an item's interval is used up, 0..1, by distance or by date, whichever is further
     * along; null when neither side can be measured. Months count as 30.44 days, the calendar mean,
     * which only moves the bar, never the due state.
     */
    fun intervalUsed(status: MaintenanceStatus): Float? {
        val item = status.item
        val byKm = status.kmLeft?.takeIf { item.intervalKm > 0 }?.let { 1.0 - it / item.intervalKm }
        val byDays = status.daysLeft?.takeIf { item.intervalMonths > 0 }?.let { 1.0 - it / (item.intervalMonths * DAYS_PER_MONTH) }
        val used = listOfNotNull(byKm, byDays).maxOrNull() ?: return if (status.daysLeft != null && status.daysLeft < 0) 1f else null
        return used.coerceIn(0.0, 1.0).toFloat()
    }

    private const val DAYS_PER_MONTH = 30.44
}
