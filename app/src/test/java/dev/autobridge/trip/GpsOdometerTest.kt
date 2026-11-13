package dev.autobridge.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsOdometerTest {
    // One thousandth of a degree of latitude is about 111 m.
    private fun fix(latSteps: Int, atSec: Long, accuracy: Float? = 5f) =
        GpsOdometer.Fix(13.7 + latSteps * 0.001, 100.5, atSec * 1000, accuracy)

    @Test fun haversineMatchesAKnownDistance() {
        // Bangkok (Victory Monument) to Ayutthaya is about 66 km in a straight line.
        val d = GpsOdometer.distanceMeters(13.7649, 100.5383, 14.3532, 100.5689)
        assertEquals(65_500.0, d, 1_000.0)
    }

    @Test fun drivingAddsEachStep() {
        val odo = GpsOdometer()
        assertFalse(odo.onFix(fix(0, 0)))
        (1..10).forEach { assertTrue(odo.onFix(fix(it, it * 10L))) }
        assertEquals(1_112.0, odo.meters, 15.0)
    }

    @Test fun aParkedPhoneWanderingInsideItsErrorAddsNothing() {
        val odo = GpsOdometer()
        odo.onFix(fix(0, 0))
        // 0.0001 degree is about 11 m, under the 15 m step.
        repeat(50) { odo.onFix(GpsOdometer.Fix(13.7 + (it % 2) * 0.0001, 100.5, (it + 1) * 1000L, 10f)) }
        assertEquals(0.0, odo.meters, 0.0)
    }

    @Test fun inaccurateFixesAreIgnored() {
        val odo = GpsOdometer()
        odo.onFix(fix(0, 0))
        assertFalse(odo.onFix(fix(5, 60, accuracy = 80f)))
        assertEquals(0.0, odo.meters, 0.0)
    }

    @Test fun aJumpFasterThanACarIsAGlitch() {
        val odo = GpsOdometer()
        odo.onFix(fix(0, 0))
        // 11 km in 10 s.
        assertFalse(odo.onFix(fix(100, 10)))
        assertTrue(odo.onFix(fix(1, 20)))
        assertEquals(111.0, odo.meters, 5.0)
    }

    @Test fun aGapIsBridgedWithTheStraightLine() {
        val odo = GpsOdometer()
        odo.onFix(fix(0, 0))
        // No fixes for 10 minutes while another app had the screen, then 11 km further on.
        assertTrue(odo.onFix(fix(100, 600)))
        assertEquals(11_120.0, odo.meters, 50.0)
    }

    @Test fun stateCarriesOverToANewInstance() {
        val first = GpsOdometer()
        first.onFix(fix(0, 0))
        first.onFix(fix(2, 20))
        val second = GpsOdometer(first.state)
        second.onFix(fix(4, 40))
        assertEquals(444.0, second.meters, 10.0)
    }
}
