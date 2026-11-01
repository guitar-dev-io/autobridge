package dev.autobridge.obd

/** Everything the gauges can show. [unit] is the unit [Reading.value] is stored in. */
enum class Gauge(val unit: String) {
    RPM("rpm"),
    SPEED("km/h"),
    COOLANT("°C"),
    LOAD("%"),
    INTAKE("°C"),
    MAP("kPa"),
    BARO("kPa"),
    BOOST("psi"),
    BATTERY("V"),
    ATF("°C"),
    THROTTLE("%"),
    FUEL_LEVEL("%"),
    AMBIENT("°C"),
}

data class Reading(val value: Float, val atMs: Long)

/**
 * The latest value of each gauge. A value older than [STALE_MS] reads as missing, so a gauge never
 * keeps showing a number the adapter stopped sending.
 */
data class Telemetry(val readings: Map<Gauge, Reading> = emptyMap()) {

    fun value(gauge: Gauge, nowMs: Long): Float? {
        val reading = readings[gauge] ?: return null
        return if (nowMs - reading.atMs <= STALE_MS) reading.value else null
    }

    /** A copy with [value] stored, and boost recomputed when it depends on the gauge that changed. */
    fun with(gauge: Gauge, value: Float, nowMs: Long): Telemetry {
        val next = readings.toMutableMap()
        next[gauge] = Reading(value, nowMs)
        if (gauge == Gauge.MAP || gauge == Gauge.BARO) {
            val map = next[Gauge.MAP]
            val baro = next[Gauge.BARO]
            if (map != null && baro != null) {
                next[Gauge.BOOST] = Reading(kpaToPsi(map.value - baro.value), nowMs)
            }
        }
        return Telemetry(next)
    }

    companion object {
        const val STALE_MS = 6_000L
        fun kpaToPsi(kpa: Float): Float = kpa * 0.145038f
    }
}

/** How worried a reading should look. */
enum class Zone { COLD, NORMAL, CAUTION, DANGER }

/**
 * The ranges that decide a [Zone]. Between [safeMin] and [safeMax] is normal; outside it the value
 * is only a caution until it passes [cautionMin] / [cautionMax], and a side that cannot be a
 * problem ([lowIsBad] / [highIsBad] false) is always normal. [coldBelow] marks a warm-up band for
 * temperatures, which reads as cold rather than as a fault.
 */
data class GaugeSpec(
    val gauge: Gauge,
    val min: Float,
    val max: Float,
    val safeMin: Float,
    val safeMax: Float,
    val cautionMin: Float,
    val cautionMax: Float,
    val lowIsBad: Boolean,
    val highIsBad: Boolean,
    val coldBelow: Float? = null,
) {
    fun zone(value: Float): Zone {
        if (value in safeMin..safeMax) return Zone.NORMAL
        if (value < safeMin) {
            coldBelow?.let { if (value < it) return Zone.COLD }
            if (!lowIsBad) return Zone.NORMAL
            return if (value >= cautionMin) Zone.CAUTION else Zone.DANGER
        }
        if (!highIsBad) return Zone.NORMAL
        return if (value <= cautionMax) Zone.CAUTION else Zone.DANGER
    }

    /** 0..1 along the gauge's own range, for a dial or a bar. */
    fun fraction(value: Float): Float = ((value - min) / (max - min)).coerceIn(0f, 1f)

    companion object {
        /** Ranges for a Ford Ranger PJ/PK diesel, from the Ford OBD2 Smart Gauge project's tuning. */
        private val SPECS: Map<Gauge, GaugeSpec> = listOf(
            GaugeSpec(Gauge.RPM, 0f, 5000f, 600f, 4500f, 300f, 5500f, lowIsBad = true, highIsBad = true),
            GaugeSpec(Gauge.SPEED, 0f, 200f, 0f, 120f, 0f, 140f, lowIsBad = false, highIsBad = true),
            GaugeSpec(Gauge.BOOST, -15f, 25f, -5f, 12f, -10f, 18f, lowIsBad = false, highIsBad = true),
            GaugeSpec(Gauge.COOLANT, 40f, 120f, 80f, 90f, 70f, 98f, lowIsBad = false, highIsBad = true, coldBelow = 70f),
            GaugeSpec(Gauge.ATF, 40f, 120f, 80f, 90f, 70f, 98f, lowIsBad = false, highIsBad = true, coldBelow = 70f),
            GaugeSpec(Gauge.BATTERY, 10f, 16f, 13.8f, 14.4f, 12.5f, 15f, lowIsBad = true, highIsBad = true),
            GaugeSpec(Gauge.FUEL_LEVEL, 0f, 100f, 25f, 100f, 15f, 100f, lowIsBad = true, highIsBad = false),
            GaugeSpec(Gauge.LOAD, 0f, 100f, 15f, 85f, 10f, 95f, lowIsBad = false, highIsBad = true),
            GaugeSpec(Gauge.INTAKE, -10f, 100f, 15f, 50f, -10f, 70f, lowIsBad = false, highIsBad = true),
            GaugeSpec(Gauge.AMBIENT, -10f, 60f, -10f, 45f, -20f, 55f, lowIsBad = true, highIsBad = true),
            GaugeSpec(Gauge.MAP, 0f, 255f, 20f, 105f, 10f, 120f, lowIsBad = true, highIsBad = true),
            GaugeSpec(Gauge.BARO, 70f, 120f, 85f, 110f, 70f, 120f, lowIsBad = true, highIsBad = true),
            GaugeSpec(Gauge.THROTTLE, 0f, 100f, 0f, 100f, 0f, 100f, lowIsBad = false, highIsBad = false),
        ).associateBy { it.gauge }

        fun of(gauge: Gauge): GaugeSpec = SPECS.getValue(gauge)
    }
}
