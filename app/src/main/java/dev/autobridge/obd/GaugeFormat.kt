package dev.autobridge.obd

import kotlin.math.roundToInt

/** How a gauge value is written for the person looking at it. Values are stored in metric units. */
object GaugeFormat {
    private val TEMPERATURES = setOf(Gauge.COOLANT, Gauge.INTAKE, Gauge.ATF, Gauge.AMBIENT)

    fun convert(gauge: Gauge, value: Float, imperial: Boolean): Float = when {
        !imperial -> value
        gauge in TEMPERATURES -> value * 9f / 5f + 32f
        gauge == Gauge.SPEED -> value * 0.621371f
        else -> value
    }

    fun unit(gauge: Gauge, imperial: Boolean): String = when {
        imperial && gauge in TEMPERATURES -> "°F"
        imperial && gauge == Gauge.SPEED -> "mph"
        else -> gauge.unit
    }

    fun text(gauge: Gauge, value: Float, imperial: Boolean): String {
        val shown = convert(gauge, value, imperial)
        return when (gauge) {
            Gauge.BATTERY, Gauge.BOOST -> String.format(java.util.Locale.US, "%.1f", shown)
            else -> shown.roundToInt().toString()
        }
    }
}
