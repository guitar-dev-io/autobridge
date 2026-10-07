package dev.autobridge.trip

import android.content.Context
import dev.autobridge.R
import java.text.NumberFormat
import kotlin.math.roundToLong

/** The wording shared by the phone screen and the car screen. */
object TripText {
    private fun whole(value: Double) = NumberFormat.getIntegerInstance().format(value.roundToLong())

    fun duration(context: Context, ms: Long): String {
        val minutes = ms / 60_000
        return if (minutes >= 60) context.getString(R.string.trip_hours_minutes, minutes / 60, minutes % 60)
        else context.getString(R.string.trip_minutes, minutes)
    }

    /** "312 km · 4 h 10 min", whatever of it is known. */
    fun summary(context: Context, trip: Trip, currentKm: Double?, nowMs: Long): String = listOfNotNull(
        TripStats.distanceKm(trip, currentKm)?.let { context.getString(R.string.trip_km, whole(it)) },
        duration(context, TripStats.durationMs(trip, nowMs)),
    ).joinToString(" · ")

    /** "1,500 baht · 300 baht each", or null when there is no cost to share yet. */
    fun cost(context: Context, trip: Trip, currentKm: Double?, bahtPerKm: Double? = trip.bahtPerKm): String? {
        val total = TripStats.totalBaht(trip, currentKm, bahtPerKm) ?: return null
        val each = TripStats.perPersonBaht(trip, currentKm, bahtPerKm) ?: return null
        return context.getString(R.string.trip_cost, whole(total), trip.people, whole(each))
    }
}
