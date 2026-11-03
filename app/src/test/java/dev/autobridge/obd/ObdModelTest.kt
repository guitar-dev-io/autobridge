package dev.autobridge.obd

import org.junit.Assert.assertEquals
import dev.autobridge.obd.profile.BuiltInProfiles
import org.junit.Assert.assertNull
import org.junit.Test

class ObdModelTest {
    @org.junit.After fun resetProfile() { use(BuiltInProfiles.GENERIC_ID) }

    private fun use(id: String) { GaugeCatalog.profile = BuiltInProfiles.findResolved(id)!! }

    private fun zone(gauge: Gauge, value: Float) = GaugeSpec.of(gauge).zone(value)

    @Test fun coolantWarmsUpThroughColdAndNormalToDanger() {
        assertEquals(Zone.COLD, zone(Gauge.COOLANT, 60f))
        assertEquals(Zone.NORMAL, zone(Gauge.COOLANT, 75f))
        assertEquals(Zone.NORMAL, zone(Gauge.COOLANT, 85f))
        assertEquals(Zone.NORMAL, zone(Gauge.COOLANT, 92f))
        assertEquals(Zone.CAUTION, zone(Gauge.COOLANT, 100f))
        assertEquals(Zone.DANGER, zone(Gauge.COOLANT, 106f))
        // The gearbox oil gauge exists only in a Ford profile.
        use("ford_ranger_pjpk")
        assertEquals(Zone.CAUTION, zone(Gauge.ATF, 95f))
        assertEquals(Zone.DANGER, zone(Gauge.ATF, 101f))
    }

    @Test fun theNextGenRangerRunsHotterBeforeItWarns() {
        use("ford_ranger_next_gen")
        assertEquals(Zone.NORMAL, zone(Gauge.COOLANT, 100f))
        assertEquals(Zone.CAUTION, zone(Gauge.COOLANT, 110f))
        assertEquals(Zone.DANGER, zone(Gauge.COOLANT, 116f))
        assertEquals(Zone.NORMAL, zone(Gauge.BOOST, 20f))
        assertEquals(Zone.CAUTION, zone(Gauge.ATF, 110f))
        use("ford_ranger_pjpk")
        assertEquals(Zone.DANGER, zone(Gauge.ATF, 105f))
        use(BuiltInProfiles.GENERIC_ID)
        assertEquals(Zone.CAUTION, zone(Gauge.COOLANT, 100f))
        assertEquals(Zone.CAUTION, zone(Gauge.BOOST, 15f))
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
        val derived = GaugeCatalog.derived
        val t = Telemetry().with(Gauge.BARO, 100f, 0).refreshDerived(derived, Gauge.BARO, 0)
            .with(Gauge.MAP, 200f, 0).refreshDerived(derived, Gauge.MAP, 0)
        assertEquals(14.5038f, t.value(Gauge.BOOST, 0)!!, 0.001f)
        val vacuum = Telemetry().with(Gauge.BARO, 100f, 0).with(Gauge.MAP, 40f, 0).refreshDerived(derived, Gauge.MAP, 0)
        assertEquals(-8.70228f, vacuum.value(Gauge.BOOST, 0)!!, 0.001f)
    }

    @Test fun aDerivedGaugeNeedsEverySourceFresh() {
        val derived = GaugeCatalog.derived
        // Only one source: nothing to derive yet.
        val one = Telemetry().with(Gauge.MAP, 200f, 0).refreshDerived(derived, Gauge.MAP, 0)
        assertNull(one.value(Gauge.BOOST, 0))
        // An old source does not count.
        val old = Telemetry().with(Gauge.BARO, 100f, 0).with(Gauge.MAP, 200f, Telemetry.STALE_MS + 10)
            .refreshDerived(derived, Gauge.MAP, Telemetry.STALE_MS + 10)
        assertNull(old.value(Gauge.BOOST, Telemetry.STALE_MS + 10))
        // A gauge that is not a source changes nothing.
        val other = Telemetry().with(Gauge.RPM, 800f, 0).refreshDerived(derived, Gauge.RPM, 0)
        assertNull(other.value(Gauge.BOOST, 0))
    }

    @Test fun theCatalogFollowsTheActiveProfile() {
        use(BuiltInProfiles.GENERIC_ID)
        assertNull(GaugeCatalog.info(Gauge.ATF))
        assertEquals(true, Gauge.BOOST in GaugeCatalog.gauges())
        use("ford_ranger_pjpk")
        assertEquals("°C", GaugeCatalog.info(Gauge.ATF)!!.unit)
        assertEquals("psi", GaugeCatalog.info(Gauge.BOOST)!!.unit)
        assertEquals(Zone.NORMAL, GaugeSpec.of(Gauge("not_a_gauge")).zone(500f))
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
