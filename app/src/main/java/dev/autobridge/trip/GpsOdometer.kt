package dev.autobridge.trip

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A trip's distance from the phone's GPS, for cars that give no odometer (no Android Auto mileage,
 * no OBD adapter).
 *
 * Each accepted fix adds the straight line from the previous accepted one. That makes gaps cheap:
 * if Android stops sending fixes while another app is on the car screen, the next fix still adds
 * the straight line across the gap, so the total only loses the curves it did not see and the
 * figure is a lower bound, never an invention.
 *
 * Filtering, in order:
 * - a fix less accurate than [MAX_ACCURACY_M] is ignored;
 * - a fix closer to the anchor than [MIN_STEP_M] or its own accuracy is jitter, ignored without
 *   moving the anchor, so a parked phone wandering inside its error circle adds nothing;
 * - a fix implying more than [MAX_SPEED_MPS] from the anchor is a glitch and ignored.
 *
 * Pure and plain-data ([State]) so it is a JVM test and its state can be saved between processes.
 */
class GpsOdometer(state: State = State()) {

    data class Fix(val lat: Double, val lon: Double, val atMs: Long, val accuracyM: Float?)

    data class State(
        val meters: Double = 0.0,
        val anchorLat: Double? = null,
        val anchorLon: Double? = null,
        val anchorMs: Long = 0L,
    )

    var state: State = state
        private set

    val meters: Double get() = state.meters

    /** Feeds one fix; returns true when it moved the total. */
    fun onFix(fix: Fix): Boolean {
        val accuracy = fix.accuracyM
        if (accuracy != null && accuracy > MAX_ACCURACY_M) return false
        if (!fix.lat.isFinite() || !fix.lon.isFinite()) return false
        val anchorLat = state.anchorLat
        val anchorLon = state.anchorLon
        if (anchorLat == null || anchorLon == null) {
            state = state.copy(anchorLat = fix.lat, anchorLon = fix.lon, anchorMs = fix.atMs)
            return false
        }
        val step = distanceMeters(anchorLat, anchorLon, fix.lat, fix.lon)
        if (step < max(MIN_STEP_M, (accuracy ?: 0f).toDouble())) return false
        val seconds = (fix.atMs - state.anchorMs) / 1000.0
        if (seconds <= 0 || step / seconds > MAX_SPEED_MPS) return false
        state = State(state.meters + step, fix.lat, fix.lon, fix.atMs)
        return true
    }

    companion object {
        const val MAX_ACCURACY_M = 35f
        const val MIN_STEP_M = 15.0
        /** About 250 km/h. */
        const val MAX_SPEED_MPS = 70.0
        private const val EARTH_RADIUS_M = 6_371_008.8

        /** Great-circle distance (haversine). */
        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }
    }
}
