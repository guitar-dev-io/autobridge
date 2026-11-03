package dev.autobridge.obd

import kotlin.math.roundToInt

/**
 * How a gauge value is written for the person looking at it. Values are stored in the unit the
 * profile gives them; the conversion to imperial goes by that unit (°C to °F, km/h to mph), so a
 * gauge a profile adds is converted without being known by name.
 */
object GaugeFormat {
    private const val CELSIUS = "°C"
    private const val KMH = "km/h"
    private val ONE_DECIMAL = setOf("V", "psi", "bar")

    private fun baseUnit(gauge: Gauge): String = GaugeCatalog.info(gauge)?.unit.orEmpty()

    fun convert(gauge: Gauge, value: Float, imperial: Boolean): Float = when {
        !imperial -> value
        baseUnit(gauge) == CELSIUS -> value * 9f / 5f + 32f
        baseUnit(gauge) == KMH -> value * 0.621371f
        else -> value
    }

    fun unit(gauge: Gauge, imperial: Boolean): String {
        val unit = baseUnit(gauge)
        return when {
            imperial && unit == CELSIUS -> "°F"
            imperial && unit == KMH -> "mph"
            else -> unit
        }
    }

    fun text(gauge: Gauge, value: Float, imperial: Boolean): String {
        val shown = convert(gauge, value, imperial)
        return if (baseUnit(gauge) in ONE_DECIMAL) String.format(java.util.Locale.US, "%.1f", shown)
        else shown.roundToInt().toString()
    }
}
