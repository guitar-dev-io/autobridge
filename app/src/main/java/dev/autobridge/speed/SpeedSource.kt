package dev.autobridge.speed

import android.os.SystemClock

/** Where a speed reading came from. Lower [priority] wins when multiple sources are fresh. */
enum class SpeedOrigin(val label: String, val priority: Int) {
    CAR("CAR", 0),      // Android Auto Car Hardware API (most authoritative)
    GPS("GPS", 1),      // Phone GPS fallback
    OBD("OBD", 2),      // OBD-II adapter (optional)
    NONE("--", 99)
}

/**
 * A single speed reading, normalized to km/h. [valid] is false when the underlying source reports
 * no usable value (e.g. CarValue status != SUCCESS, or GPS has no fix yet).
 */
data class SpeedSample(
    val kmh: Float,
    val origin: SpeedOrigin,
    val valid: Boolean,
    val timestampMs: Long = SystemClock.elapsedRealtime()
) {
    companion object {
        val NONE = SpeedSample(0f, SpeedOrigin.NONE, valid = false)
    }
}

/**
 * A pluggable speed provider. Implementations push samples to [onSample] whenever they get a new
 * reading. This layer is display-only and independent of the safety [dev.autobridge.safety]
 * gate — it never gates features and never alters ParkingStateStore.
 */
interface SpeedSource {
    val origin: SpeedOrigin

    /** True if the source can currently produce data (permission granted, hardware present, etc.). */
    fun isAvailable(): Boolean

    fun start(onSample: (SpeedSample) -> Unit)

    fun stop()
}
