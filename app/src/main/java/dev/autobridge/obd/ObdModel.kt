package dev.autobridge.obd

import dev.autobridge.obd.profile.DerivedDef
import dev.autobridge.obd.profile.Formula
import dev.autobridge.obd.profile.ZoneRange

/**
 * A thing the gauges can show, named by the id the vehicle profile gives it ("rpm", "coolant"...).
 * Which ones exist, what they are called and in what unit comes from the active profile through
 * [GaugeCatalog]; the constants here are the ids the built-in profiles use.
 */
data class Gauge(val id: String) {
    override fun toString(): String = id

    companion object {
        val RPM = Gauge("rpm")
        val SPEED = Gauge("speed")
        val COOLANT = Gauge("coolant")
        val LOAD = Gauge("load")
        val INTAKE = Gauge("intake")
        val MAP = Gauge("map")
        val BARO = Gauge("baro")
        val BOOST = Gauge("boost")
        val BATTERY = Gauge("battery")
        val ATF = Gauge("atf")
        val THROTTLE = Gauge("throttle")
        val FUEL_LEVEL = Gauge("fuel")
        val AMBIENT = Gauge("ambient")

        /**
         * The ids older versions saved pages with (the enum names), mapped to today's. A saved value
         * that is neither is an id from some other profile and is kept as it is.
         */
        fun fromSaved(name: String): Gauge {
            val text = name.trim()
            return when (text.uppercase()) {
                "FUEL_LEVEL" -> FUEL_LEVEL
                else -> Gauge(text.lowercase())
            }
        }
    }
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

    /** A copy with [value] stored for [gauge]. */
    fun with(gauge: Gauge, value: Float, nowMs: Long): Telemetry =
        Telemetry(readings + (gauge to Reading(value, nowMs)))

    /**
     * A copy with every derived gauge in [defs] that depends on [changed] recomputed from its
     * sources. A gauge whose sources are not all fresh keeps its old reading, which then goes stale
     * on its own rather than showing a number built from one old and one new value.
     */
    fun refreshDerived(defs: List<DerivedDef>, changed: Gauge, nowMs: Long): Telemetry {
        var next = this
        for (def in defs) {
            if (changed.id !in def.sources) continue
            val inputs = def.sources.map { next.value(Gauge(it), nowMs) ?: return@map null }
            if (inputs.any { it == null }) continue
            val formula = Formula.parse(def.formula) ?: continue
            val result = formula.evaluate(DoubleArray(inputs.size) { inputs[it]!!.toDouble() }) ?: continue
            next = next.with(Gauge(def.gauge), result, nowMs)
        }
        return next
    }

    companion object {
        const val STALE_MS = 6_000L
    }
}

/** How worried a reading should look. */
enum class Zone { COLD, NORMAL, CAUTION, DANGER }

/**
 * The ranges that decide a [Zone], and the range a dial covers. Built from the active vehicle
 * profile by [of]; a gauge the profile gives no zones is always [Zone.NORMAL].
 */
data class GaugeSpec(
    val gauge: Gauge,
    val min: Float,
    val max: Float,
    val zones: ZoneRange?,
) {
    fun zone(value: Float): Zone {
        val z = zones ?: return Zone.NORMAL
        if (value in z.safeMin..z.safeMax) return Zone.NORMAL
        if (value < z.safeMin) {
            z.coldBelow?.let { if (value < it) return Zone.COLD }
            if (!z.lowIsBad) return Zone.NORMAL
            return if (value >= z.cautionMin) Zone.CAUTION else Zone.DANGER
        }
        if (!z.highIsBad) return Zone.NORMAL
        return if (value <= z.cautionMax) Zone.CAUTION else Zone.DANGER
    }

    /** 0..1 along the gauge's own range, for a dial or a bar. */
    fun fraction(value: Float): Float = ((value - min) / (max - min)).coerceIn(0f, 1f)

    companion object {
        /** A gauge the profile does not know: a 0 to 100 dial with no zones. */
        private fun unknown(gauge: Gauge) = GaugeSpec(gauge, 0f, 100f, null)

        fun of(gauge: Gauge): GaugeSpec = GaugeCatalog.info(gauge)?.spec ?: unknown(gauge)
    }
}
