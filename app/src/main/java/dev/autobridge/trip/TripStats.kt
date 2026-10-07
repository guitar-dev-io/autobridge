package dev.autobridge.trip

/**
 * One trip: started and ended at an odometer reading, shared between [people]. [endMs] is 0 while
 * it is still going. [otherBaht] is what the fuel does not cover (tolls, parking), and
 * [bahtPerKm] the fuel or charging cost per km at the moment the trip ended, kept so the figure
 * of a past trip does not shift when the fuel log changes later.
 */
data class Trip(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val startKm: Double?,
    val endKm: Double?,
    val people: Int,
    val otherBaht: Double = 0.0,
    val bahtPerKm: Double? = null,
) {
    val active: Boolean get() = endMs == 0L
}

/** The figures for a trip, pure so they are tested on the JVM. Null where there is not enough data. */
object TripStats {
    /** Distance driven, or null when a reading is missing or the end is not past the start. */
    fun distanceKm(trip: Trip, currentKm: Double? = null): Double? {
        val start = trip.startKm ?: return null
        val end = trip.endKm ?: currentKm ?: return null
        return (end - start).takeIf { it > 0 }
    }

    /** Fuel or charging for the distance, from the trip's cost per km. */
    fun energyBaht(trip: Trip, currentKm: Double? = null, bahtPerKm: Double? = trip.bahtPerKm): Double? {
        val distance = distanceKm(trip, currentKm) ?: return null
        return bahtPerKm?.let { it * distance }
    }

    /** Energy plus tolls and parking; null when there is neither an estimate nor any extra cost. */
    fun totalBaht(trip: Trip, currentKm: Double? = null, bahtPerKm: Double? = trip.bahtPerKm): Double? {
        val energy = energyBaht(trip, currentKm, bahtPerKm)
        if (energy == null && trip.otherBaht <= 0) return null
        return (energy ?: 0.0) + trip.otherBaht
    }

    /** Each person's share of [totalBaht]. */
    fun perPersonBaht(trip: Trip, currentKm: Double? = null, bahtPerKm: Double? = trip.bahtPerKm): Double? =
        totalBaht(trip, currentKm, bahtPerKm)?.let { it / trip.people.coerceAtLeast(1) }

    fun durationMs(trip: Trip, nowMs: Long): Long = ((if (trip.active) nowMs else trip.endMs) - trip.startMs).coerceAtLeast(0)
}
