package dev.autobridge.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObdModelTest {
    private fun zone(gauge: Gauge, value: Float) = GaugeSpec.of(gauge).zone(value)

    @Test fun coolantWarmsUpThroughColdAndNormalToDanger() {
        assertEquals(Zone.COLD, zone(Gauge.COOLANT, 60f))
        assertEquals(Zone.NORMAL, zone(Gauge.COOLANT, 75f))
        assertEquals(Zone.NORMAL, zone(Gauge.COOLANT, 85f))
        assertEquals(Zone.NORMAL, zone(Gauge.COOLANT, 92f))
        assertEquals(Zone.CAUTION, zone(Gauge.COOLANT, 100f))
        assertEquals(Zone.DANGER, zone(Gauge.COOLANT, 106f))
        assertEquals(Zone.CAUTION, zone(Gauge.ATF, 95f))
        assertEquals(Zone.DANGER, zone(Gauge.ATF, 101f))
    }

    @Test fun batteryIsBadWhenLowOrWhenTooHigh() {
        assertEquals(Zone.NORMAL, zone(Gauge.BATTERY, 14.1f))
        assertEquals(Zone.CAUTION, zone(Gauge.BATTERY, 13.0f))
        assertEquals(Zone.DANGER, zone(Gauge.BATTERY, 12.0f))
        assertEquals(Zone.DANGER, zone(Gauge.BATTERY, 15.6f))
    }

    @Test fun aSideThatCannotBeAProblemIsNormal() {
        assertEquals(Zone.NORMAL, zone(Gauge.THROTTLE, 100f))
        assertEquals(Zone.NORMAL, zone(Gauge.LOAD, 5f))
        assertEquals(Zone.DANGER, zone(Gauge.LOAD, 99f))
    }

    @Test fun fractionIsClamped() {
        assertEquals(0.5f, GaugeSpec.of(Gauge.RPM).fraction(2500f), 0.001f)
        assertEquals(1f, GaugeSpec.of(Gauge.RPM).fraction(9000f), 0f)
        assertEquals(0f, GaugeSpec.of(Gauge.RPM).fraction(-5f), 0f)
    }

    @Test fun boostIsMapMinusBarometricInPsi() {
        val t = Telemetry().with(Gauge.BARO, 100f, 0).with(Gauge.MAP, 200f, 0)
        assertEquals(14.5038f, t.value(Gauge.BOOST, 0)!!, 0.001f)
        val vacuum = Telemetry().with(Gauge.BARO, 100f, 0).with(Gauge.MAP, 40f, 0)
        assertEquals(-8.70228f, vacuum.value(Gauge.BOOST, 0)!!, 0.001f)
    }

    @Test fun anOldReadingIsMissing() {
        val t = Telemetry().with(Gauge.RPM, 900f, 1_000)
        assertEquals(900f, t.value(Gauge.RPM, 1_000 + Telemetry.STALE_MS)!!, 0f)
        assertNull(t.value(Gauge.RPM, 1_000 + Telemetry.STALE_MS + 1))
        assertNull(t.value(Gauge.SPEED, 1_000))
    }

    @Test fun everyGaugeWithAZoneHasASpec() {
        for (gauge in listOf(Gauge.RPM, Gauge.SPEED, Gauge.COOLANT, Gauge.ATF, Gauge.BATTERY, Gauge.BOOST, Gauge.LOAD)) {
            GaugeSpec.of(gauge)
        }
    }

    @Test fun scannerRecognisesTheUsualAdapters() {
        assertEquals(true, ObdAdapterMatcher.isLikely("vLinker FD+", emptyList()))
        assertEquals(true, ObdAdapterMatcher.isLikely("IOS-Vlink", listOf(BleTransport.uuid16(0x18F0))))
        assertEquals(true, ObdAdapterMatcher.isLikely(null, listOf(BleTransport.uuid16(0xFFF0))))
        assertEquals(false, ObdAdapterMatcher.isLikely("JBL Flip", emptyList()))
    }
}
