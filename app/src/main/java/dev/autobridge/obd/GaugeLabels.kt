package dev.autobridge.obd

import android.content.Context
import dev.autobridge.R

/**
 * The name each gauge goes by on screen, shared by the phone and the car. The gauges every built-in
 * profile has are translated (Thai and English); a gauge a profile adds is shown under the label
 * the profile gives it, and one nobody knows under its id.
 */
object GaugeLabels {
    private fun resource(gauge: Gauge): Int? = when (gauge) {
        Gauge.RPM -> R.string.obd_g_rpm
        Gauge.SPEED -> R.string.obd_g_speed
        Gauge.COOLANT -> R.string.obd_g_coolant
        Gauge.ATF -> R.string.obd_g_atf
        Gauge.BOOST -> R.string.obd_g_boost
        Gauge.BATTERY -> R.string.obd_g_battery
        Gauge.LOAD -> R.string.obd_g_load
        Gauge.INTAKE -> R.string.obd_g_intake
        Gauge.MAP -> R.string.obd_g_map
        Gauge.BARO -> R.string.obd_g_baro
        Gauge.THROTTLE -> R.string.obd_g_throttle
        Gauge.FUEL_LEVEL -> R.string.obd_g_fuel
        Gauge.AMBIENT -> R.string.obd_g_ambient
        else -> null
    }

    fun text(context: Context, gauge: Gauge): String =
        resource(gauge)?.let(context::getString) ?: GaugeCatalog.info(gauge)?.label ?: gauge.id
}
