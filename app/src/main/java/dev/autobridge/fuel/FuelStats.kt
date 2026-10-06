package dev.autobridge.fuel

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToLong

/**
 * One fill-up. Always a full tank (the way this log is meant to be used), which is what lets the
 * litres put in now stand for the fuel burned since the previous fill-up.
 *
 * [odometerKm] is null when it was not known — the car did not report it and it was not typed —
 * and such an entry still counts towards spending, just not towards consumption.
 */
data class FuelEntry(
    val id: Long,
    val timeMs: Long,
    val liters: Double,
    val totalBaht: Double,
    val odometerKm: Double?,
    val station: String = "",
) {
    val pricePerLiter: Double get() = if (liters > 0) totalBaht / liters else 0.0
}

/** What the log adds up to. Null figures are ones there is not yet enough data for. */
data class FuelSummary(
    val fillUps: Int,
    val totalLiters: Double,
    val totalBaht: Double,
    /** Distance over litres across every measurable full-to-full interval. */
    val averageKmPerLiter: Double?,
    /** The most recent interval's consumption. */
    val lastKmPerLiter: Double?,
    val bahtPerKm: Double?,
    val thisMonthBaht: Double,
    val last: FuelEntry?,
)

/**
 * The consumption maths, kept pure so it is tested on the JVM.
 *
 * Full-to-full method, in date order: each fill-up's litres refuel the distance driven since the
 * previous fill-up with an odometer reading. A fill-up without a reading still burned into the
 * next interval, so its litres and baht are carried into it. A reading that does not move forward
 * (a typo) is left out rather than allowed to produce a wild figure.
 */
object FuelStats {
    data class Interval(val entry: FuelEntry, val distanceKm: Double, val liters: Double, val baht: Double) {
        val kmPerLiter: Double get() = distanceKm / liters
    }

    fun intervals(entries: List<FuelEntry>): List<Interval> {
        val result = mutableListOf<Interval>()
        var startKm: Double? = null
        var carriedLiters = 0.0
        var carriedBaht = 0.0
        for (entry in entries.filter { it.liters > 0 }.sortedBy { it.timeMs }) {
            val odometer = entry.odometerKm
            if (odometer == null) {
                if (startKm != null) {
                    carriedLiters += entry.liters
                    carriedBaht += entry.totalBaht
                }
                continue
            }
            val start = startKm
            if (start == null) {
                startKm = odometer
                continue
            }
            if (odometer <= start) continue
            result += Interval(entry, odometer - start, carriedLiters + entry.liters, carriedBaht + entry.totalBaht)
            startKm = odometer
            carriedLiters = 0.0
            carriedBaht = 0.0
        }
        return result
    }

    fun summarize(entries: List<FuelEntry>, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): FuelSummary {
        val intervals = intervals(entries)
        val distance = intervals.sumOf { it.distanceKm }
        val intervalLiters = intervals.sumOf { it.liters }
        val intervalBaht = intervals.sumOf { it.baht }
        val month = YearMonth.from(Instant.ofEpochMilli(nowMs).atZone(zone))
        val thisMonth = entries.filter { YearMonth.from(Instant.ofEpochMilli(it.timeMs).atZone(zone)) == month }
        return FuelSummary(
            fillUps = entries.size,
            totalLiters = entries.sumOf { it.liters },
            totalBaht = entries.sumOf { it.totalBaht },
            averageKmPerLiter = if (intervalLiters > 0) distance / intervalLiters else null,
            lastKmPerLiter = intervals.lastOrNull()?.kmPerLiter,
            bahtPerKm = if (distance > 0) intervalBaht / distance else null,
            thisMonthBaht = thisMonth.sumOf { it.totalBaht },
            last = entries.maxByOrNull { it.timeMs },
        )
    }

    /** The consumption of the interval ending at [entry], if it closes one. */
    fun kmPerLiterOf(entry: FuelEntry, entries: List<FuelEntry>): Double? =
        intervals(entries).firstOrNull { it.entry.id == entry.id }?.kmPerLiter

    /** The log as CSV, oldest first, for a spreadsheet. */
    fun csv(entries: List<FuelEntry>, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        appendLine("date,liters,total_baht,price_per_liter,odometer_km,km_per_liter,station")
        entries.sortedBy { it.timeMs }.forEach { entry ->
            val date = Instant.ofEpochMilli(entry.timeMs).atZone(zone).toLocalDateTime().toString().replace('T', ' ')
            val kmPerL = kmPerLiterOf(entry, entries)
            appendLine(
                listOf(
                    date,
                    "%.2f".format(java.util.Locale.US, entry.liters),
                    "%.2f".format(java.util.Locale.US, entry.totalBaht),
                    "%.2f".format(java.util.Locale.US, entry.pricePerLiter),
                    entry.odometerKm?.let { it.roundToLong().toString() }.orEmpty(),
                    kmPerL?.let { "%.2f".format(java.util.Locale.US, it) }.orEmpty(),
                    "\"" + entry.station.replace("\"", "\"\"") + "\"",
                ).joinToString(",")
            )
        }
    }
}
