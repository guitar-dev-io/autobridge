package dev.autobridge.parking

/**
 * Which position to keep for "I parked here", pure so it is tested on the JVM.
 *
 * Waiting for a satellite fix is what made marking slow: under a roof GPS can take half a minute
 * or never arrive. So every source is asked at once (fused, Wi-Fi/cell, GPS), a reading that is
 * already good enough ends the wait, and at the deadline the best one so far is kept. A reading
 * too old or too vague to find a car by is never kept: no spot is better than a wrong one.
 */
object ParkingFixChoice {
    /** Close enough to walk back to: ends the wait at once. */
    const val GOOD_METERS = 35f

    /** The vaguest reading still worth keeping at the deadline (a building, not a district). */
    const val USABLE_METERS = 150f

    /** A remembered position this recent is as good as a new one (the phone has not moved far). */
    const val FRESH_MS = 2 * 60 * 1000L

    data class Candidate(val lat: Double, val lon: Double, val accuracyMeters: Float?, val timeMs: Long)

    /** True when [c] can end the wait now. */
    fun isGood(c: Candidate, nowMs: Long): Boolean =
        isUsable(c, nowMs) && (c.accuracyMeters ?: Float.MAX_VALUE) <= GOOD_METERS

    /** True when [c] is a real place, recent, and precise enough to keep. */
    fun isUsable(c: Candidate, nowMs: Long): Boolean =
        ParkingSpot.isValid(c.lat, c.lon) &&
            nowMs - c.timeMs in 0..FRESH_MS &&
            (c.accuracyMeters ?: Float.MAX_VALUE) <= USABLE_METERS

    /** The most precise usable reading, or null when none is fit to keep. */
    fun best(candidates: List<Candidate>, nowMs: Long): Candidate? =
        candidates.filter { isUsable(it, nowMs) }.minByOrNull { it.accuracyMeters ?: Float.MAX_VALUE }
}
