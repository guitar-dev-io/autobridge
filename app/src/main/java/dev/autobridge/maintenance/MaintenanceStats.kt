package dev.autobridge.maintenance

import java.time.Instant
import java.time.ZoneId

/**
 * One thing to look after: due every [intervalKm] km, every [intervalMonths] months, or whichever
 * comes first. Either interval may be 0 — a road tax has no distance, a tyre rotation no date.
 * [lastKm] and [lastTimeMs] are when it was last done.
 */
data class MaintenanceItem(
    val id: Long,
    val name: String,
    val intervalKm: Int,
    val intervalMonths: Int,
    val lastKm: Double?,
    val lastTimeMs: Long,
)

enum class DueState { OK, SOON, OVERDUE }

/** [kmLeft] and [daysLeft] are null when that interval is not set; negative once passed. */
data class MaintenanceStatus(val item: MaintenanceItem, val state: DueState, val kmLeft: Double?, val daysLeft: Long?)

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

    /**
     * Where [item] stands at [odometerKm] (null when no odometer is known, which leaves only the
     * date to go by) and [nowMs].
     */
    fun status(item: MaintenanceItem, odometerKm: Double?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): MaintenanceStatus {
        val kmLeft = if (item.intervalKm > 0 && item.lastKm != null && odometerKm != null) {
            item.lastKm + item.intervalKm - odometerKm
        } else null
        val daysLeft = if (item.intervalMonths > 0 && item.lastTimeMs > 0) {
            val due = Instant.ofEpochMilli(item.lastTimeMs).atZone(zone).plusMonths(item.intervalMonths.toLong()).toInstant()
            java.time.Duration.between(Instant.ofEpochMilli(nowMs), due).toDays().let {
                // Duration truncates toward zero; a due date a few hours behind is already overdue.
                if (due.isBefore(Instant.ofEpochMilli(nowMs)) && it == 0L) -1L else it
            }
        } else null
        val overdue = (kmLeft != null && kmLeft < 0) || (daysLeft != null && daysLeft < 0)
        val soon = (kmLeft != null && kmLeft <= maxOf(SOON_MIN_KM, item.intervalKm / 10.0)) ||
            (daysLeft != null && daysLeft <= SOON_DAYS)
        return MaintenanceStatus(item, if (overdue) DueState.OVERDUE else if (soon) DueState.SOON else DueState.OK, kmLeft, daysLeft)
    }

    /** Every item with its status, the most urgent first: overdue, then soon, then the rest. */
    fun ordered(items: List<MaintenanceItem>, odometerKm: Double?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<MaintenanceStatus> =
        items.map { status(it, odometerKm, nowMs, zone) }.sortedWith(
            compareByDescending<MaintenanceStatus> { it.state.ordinal }
                .thenBy { it.daysLeft ?: Long.MAX_VALUE }
                .thenBy { it.kmLeft ?: Double.MAX_VALUE }
        )
}
