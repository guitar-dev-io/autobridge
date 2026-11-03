package dev.autobridge.obd

import org.junit.Assert.assertEquals
import org.junit.Test

class GaugeFormatTest {
    @org.junit.Before fun fordProfile() { GaugeCatalog.profile = dev.autobridge.obd.profile.BuiltInProfiles.findResolved("ford_ranger_pjpk")!! }

    @org.junit.After fun resetProfile() { GaugeCatalog.profile = dev.autobridge.obd.profile.BuiltInProfiles.findResolved(dev.autobridge.obd.profile.BuiltInProfiles.GENERIC_ID)!! }

    @Test fun metricIsUnchanged() {
        assertEquals("85", GaugeFormat.text(Gauge.COOLANT, 85f, imperial = false))
        assertEquals("°C", GaugeFormat.unit(Gauge.COOLANT, imperial = false))
    }

    @Test fun imperialConvertsTemperatureAndSpeedOnly() {
        assertEquals("185", GaugeFormat.text(Gauge.COOLANT, 85f, imperial = true))
        assertEquals("62", GaugeFormat.text(Gauge.SPEED, 100f, imperial = true))
        assertEquals("1250", GaugeFormat.text(Gauge.RPM, 1250f, imperial = true))
        assertEquals("°F", GaugeFormat.unit(Gauge.ATF, imperial = true))
        assertEquals("mph", GaugeFormat.unit(Gauge.SPEED, imperial = true))
        assertEquals("psi", GaugeFormat.unit(Gauge.BOOST, imperial = true))
    }

    @Test fun batteryAndBoostKeepOneDecimal() {
        assertEquals("14.2", GaugeFormat.text(Gauge.BATTERY, 14.2f, false))
        assertEquals("-3.5", GaugeFormat.text(Gauge.BOOST, -3.5f, false))
    }
}
