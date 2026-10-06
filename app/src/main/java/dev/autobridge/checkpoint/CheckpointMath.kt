package dev.autobridge.checkpoint

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A checkpoint the driver marked: where, and what they called it. */
data class Checkpoint(val id: Long, val name: String, val lat: Double, val lon: Double, val createdMs: Long)

/** The distance maths, pure so it is tested on the JVM. */
object CheckpointMath {
    private const val EARTH_RADIUS_M = 6_371_000.0
    private val COORDINATES = Regex("""(-?\d{1,3}(?:\.\d+)?)\s*[,\s]\s*(-?\d{1,3}(?:\.\d+)?)""")

    /** Great-circle distance in metres. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    data class Near(val checkpoint: Checkpoint, val meters: Double)

    /** The [limit] checkpoints closest to the given position, nearest first. */
    fun nearest(points: List<Checkpoint>, lat: Double, lon: Double, limit: Int = 3): List<Near> =
        points.map { Near(it, distanceM(lat, lon, it.lat, it.lon)) }.sortedBy { it.meters }.take(limit)

    /**
     * A latitude and longitude out of whatever was pasted — "13.7563, 100.5018", or a map link with
     * the pair in it. Null when there is none or it is out of range.
     */
    fun parseCoordinates(text: String): Pair<Double, Double>? {
        for (match in COORDINATES.findAll(text)) {
            val lat = match.groupValues[1].toDoubleOrNull() ?: continue
            val lon = match.groupValues[2].toDoubleOrNull() ?: continue
            if (lat in -90.0..90.0 && lon in -180.0..180.0) return lat to lon
        }
        return null
    }
}
