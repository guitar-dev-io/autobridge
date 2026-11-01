package dev.autobridge.obd

import androidx.annotation.StringRes
import dev.autobridge.R

/** The name each gauge goes by on screen, shared by the phone and the car. */
object GaugeLabels {
    @StringRes
    fun of(gauge: Gauge): Int = when (gauge) {
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
    }
}
