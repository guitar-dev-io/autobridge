package dev.autobridge.maintenance

import dev.autobridge.fuel.FuelEntry
import java.time.Instant
import java.time.ZoneId

/**
 * One thing to look after: due every [intervalKm] km, every [intervalMonths] months, or whichever
 * comes first. Either interval may be 0 — a road tax has no distance, a tyre rotation no date.
 * [lastKm] and [lastTimeMs] are when it was last done. [dueDateMs], when set, is a date the driver
 * gave outright (a road tax that runs out on the 12th) and stands in for "last done + months".
 */
data class MaintenanceItem(
    val id: Long,
    val name: String,
    val intervalKm: Int,
    val intervalMonths: Int,
    val lastKm: Double?,
    val lastTimeMs: Long,
    val dueDateMs: Long = 0L,
)

enum class DueState { OK, SOON, OVERDUE }

/**
 * [kmLeft] and [daysLeft] are null when that interval is not set; negative once passed. [dueMs] is
 * when it falls due: the date, or the day the distance is reached at the driver's usual pace,
 * whichever is first; null when neither can be told.
 */
data class MaintenanceStatus(
    val item: MaintenanceItem,
    val state: DueState,
    val kmLeft: Double?,
    val daysLeft: Long?,
    val dueMs: Long? = null,
)

/** What a new vehicle is offered to start with. Names are string resources, resolved by the UI. */
data class MaintenancePreset(val key: String, val intervalKm: Int, val intervalMonths: Int)

/** The due-date maths, pure so it is tested on the JVM. */
object MaintenanceStats {
    /** "Soon" is the last tenth of a distance interval (at least 300 km), or the last 30 days. */
    private const val SOON_DAYS = 30L
    private const val SOON_MIN_KM = 300.0

    val FUEL_PRESETS = listOf(
        MaintenancePreset("engine_oil", 10_000, 12),
        MaintenancePreset("tires", 10_000, 0),
        MaintenancePreset("air_filter", 20_000, 24),
        MaintenancePreset("brake_fluid", 40_000, 24),
        MaintenancePreset("road_tax", 0, 12),
    )

    /** An EV has no oil or air filter; it has a cabin filter, a battery coolant and a 12 V battery. */
    val EV_PRESETS = listOf(
        MaintenancePreset("tires", 10_000, 0),
        MaintenancePreset("cabin_filter", 20_000, 12),
        MaintenancePreset("brake_fluid", 40_000, 24),
        MaintenancePreset("battery_coolant", 80_000, 48),
        MaintenancePreset("battery_12v", 0, 36),
        MaintenancePreset("road_tax", 0, 12),
    )

    fun presets(ev: Boolean): List<MaintenancePreset> = if (ev) EV_PRESETS else FUEL_PRESETS

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** The date [item] falls due by the calendar, or null when it has no date side. */
    fun dateDueMs(item: MaintenanceItem, zone: ZoneId = ZoneId.systemDefault()): Long? = when {
        item.dueDateMs > 0 -> item.dueDateMs
        item.intervalMonths > 0 && item.lastTimeMs > 0 ->
            Instant.ofEpochMilli(item.lastTimeMs).atZone(zone).plusMonths(item.intervalMonths.toLong()).toInstant().toEpochMilli()
        else -> null
    }

    /**
     * How far the car goes in a day, from the first and last fill-ups that carry an odometer. Null
     * until there are two at least two weeks apart that moved forward: a shorter stretch says
     * little about a driver's pace.
     */
    fun kmPerDay(entries: List<FuelEntry>): Double? {
        val dated = entries.filter { it.odometerKm != null }.sortedBy { it.timeMs }
        if (dated.size < 2) return null
        val days = (dated.last().timeMs - dated.first().timeMs).toDouble() / DAY_MS
        val km = dated.last().odometerKm!! - dated.first().odometerKm!!
        return if (days >= 14 && km > 0) km / days else null
    }

    /**
     * Where [item] stands at [odometerKm] (null when no odometer is known, which leaves only the
     * date to go by) and [nowMs]. [kmPerDay], when known, turns the distance left into a date.
     */
    fun status(
        item: MaintenanceItem,
        odometerKm: Double?,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        kmPerDay: Double? = null,
    ): MaintenanceStatus {
        val kmLeft = if (item.intervalKm > 0 && item.lastKm != null && odometerKm != null) {
            item.lastKm + item.intervalKm - odometerKm
        } else null
        val dateDue = dateDueMs(item, zone)
        val daysLeft = dateDue?.let { due ->
            // Math.floorDiv rounds toward the past: a due date hours behind is already -1 days.
            Math.floorDiv(due - nowMs, DAY_MS)
        }
        val kmDue = if (kmLeft != null && kmLeft >= 0 && kmPerDay != null && kmPerDay > 0) {
            nowMs + (kmLeft / kmPerDay * DAY_MS).toLong()
        } else null
        val overdue = (kmLeft != null && kmLeft < 0) || (daysLeft != null && daysLeft < 0)
        val soon = (kmLeft != null && kmLeft <= maxOf(SOON_MIN_KM, item.intervalKm / 10.0)) ||
            (daysLeft != null && daysLeft <= SOON_DAYS)
        return MaintenanceStatus(
            item,
            if (overdue) DueState.OVERDUE else if (soon) DueState.SOON else DueState.OK,
            kmLeft,
            daysLeft,
            listOfNotNull(dateDue, kmDue).minOrNull(),
        )
    }

    /** Every item with its status, the most urgent first: overdue, then soon, then the rest. */
    fun ordered(
        items: List<MaintenanceItem>,
        odometerKm: Double?,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        kmPerDay: Double? = null,
    ): List<MaintenanceStatus> =
        items.map { status(it, odometerKm, nowMs, zone, kmPerDay) }.sortedWith(
            compareByDescending<MaintenanceStatus> { it.state.ordinal }
                .thenBy { it.daysLeft ?: Long.MAX_VALUE }
                .thenBy { it.kmLeft ?: Double.MAX_VALUE }
        )
}
