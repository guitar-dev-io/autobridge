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
        /** The vehicle the zones are for; set from the saved choice by [ObdSettings.applyProfile]. */
        @Volatile
        var profile: Transmission = Transmission.T5

        private fun spec(
            gauge: Gauge, min: Float, max: Float, safeMin: Float, safeMax: Float,
            cautionMin: Float, cautionMax: Float, lowIsBad: Boolean, highIsBad: Boolean, coldBelow: Float? = null,
        ) = GaugeSpec(gauge, min, max, safeMin, safeMax, cautionMin, cautionMax, lowIsBad, highIsBad, coldBelow)

        /**
         * Ranges for a Ford Ranger PJ/PK diesel, from the Ford OBD2 Smart Gauge project's tuning.
         * Coolant and gearbox oil follow its README table (normal up to 95 / 90, caution up to 105 / 100):
         * a Ranger at 92 degrees is simply warm, and its gradient code's tighter 90 degree edge made it orange.
         */
        private val BASE: Map<Gauge, GaugeSpec> = listOf(
            spec(Gauge.RPM, 0f, 5000f, 600f, 4500f, 300f, 5500f, lowIsBad = true, highIsBad = true),
            spec(Gauge.SPEED, 0f, 200f, 0f, 120f, 0f, 140f, lowIsBad = false, highIsBad = true),
            spec(Gauge.BOOST, -15f, 25f, -5f, 12f, -10f, 18f, lowIsBad = false, highIsBad = true),
            spec(Gauge.COOLANT, 40f, 120f, 80f, 95f, 70f, 105f, lowIsBad = false, highIsBad = true, coldBelow = 70f),
            spec(Gauge.ATF, 40f, 120f, 70f, 90f, 65f, 100f, lowIsBad = false, highIsBad = true, coldBelow = 70f),
            spec(Gauge.BATTERY, 10f, 16f, 13.8f, 14.4f, 12.5f, 15f, lowIsBad = true, highIsBad = true),
            spec(Gauge.FUEL_LEVEL, 0f, 100f, 25f, 100f, 15f, 100f, lowIsBad = true, highIsBad = false),
            spec(Gauge.LOAD, 0f, 100f, 15f, 85f, 10f, 95f, lowIsBad = false, highIsBad = true),
            spec(Gauge.INTAKE, -10f, 100f, 15f, 50f, -10f, 70f, lowIsBad = false, highIsBad = true),
            spec(Gauge.AMBIENT, -10f, 60f, -10f, 45f, -20f, 55f, lowIsBad = true, highIsBad = true),
            spec(Gauge.MAP, 0f, 255f, 20f, 105f, 10f, 120f, lowIsBad = true, highIsBad = true),
            spec(Gauge.BARO, 70f, 120f, 85f, 110f, 70f, 120f, lowIsBad = true, highIsBad = true),
            spec(Gauge.THROTTLE, 0f, 100f, 0f, 100f, 0f, 100f, lowIsBad = false, highIsBad = false),
        ).associateBy { it.gauge }

        /**
         * The 2022 and later Ranger's twin-turbo diesels and V6 run hotter than the PJ/PK, spend more
         * time at 100 degrees and boost well past 12 psi, so a PJ/PK table would show a healthy truck
         * in orange. These are wider, deliberately: a first estimate to adjust against real readings.
         */
        private val NEXT_GEN: Map<Gauge, GaugeSpec> = BASE + listOf(
            spec(Gauge.BOOST, -15f, 35f, -5f, 24f, -10f, 30f, lowIsBad = false, highIsBad = true),
            spec(Gauge.COOLANT, 40f, 130f, 80f, 106f, 70f, 114f, lowIsBad = false, highIsBad = true, coldBelow = 70f),
            spec(Gauge.ATF, 40f, 140f, 70f, 105f, 65f, 118f, lowIsBad = false, highIsBad = true, coldBelow = 70f),
            spec(Gauge.RPM, 0f, 6000f, 600f, 5000f, 300f, 6000f, lowIsBad = true, highIsBad = true),
        ).associateBy { it.gauge }

        fun of(gauge: Gauge): GaugeSpec =
            (if (profile == Transmission.NEXT_GEN) NEXT_GEN else BASE).getValue(gauge)
    }
}
