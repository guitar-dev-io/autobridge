package dev.autobridge.parking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkingSpotTest {
    @Test fun theLinkNamesThePlaceAndTheNote() {
        val spot = ParkingSpot(13.7563, 100.5018, "B2 pillar 14", 0)
        assertEquals("geo:13.756300,100.501800?q=13.756300,100.501800(B2+pillar+14)", spot.geoUri())
    }

    @Test fun theCarGetsThePlainPosition() {
        assertEquals("geo:13.756300,100.501800", ParkingSpot(13.7563, 100.5018, "B2", 0).navigationUri())
    }

    @Test fun aThaiNoteKeepsItsToneMarks() {
        val uri = ParkingSpot(13.0, 100.0, "ชั้น B2 เสา 14", 0).geoUri()
        assertTrue(uri.endsWith("(" + java.net.URLEncoder.encode("ชั้น B2 เสา 14", "UTF-8") + ")"))
    }

    @Test fun noNoteMeansNoLabel() {
        assertEquals("geo:13.756300,100.501800?q=13.756300,100.501800", ParkingSpot(13.7563, 100.5018, "", 0).geoUri())
    }

    @Test fun parenthesesAndQuotesInANoteCannotBreakTheLink() {
        val uri = ParkingSpot(1.0, 2.0, "near (lift) \"A\"", 0).geoUri()
        assertTrue(uri.endsWith("(near+lift+A)"))
        assertEquals(1, uri.count { it == '(' })
    }

    @Test fun decimalCommasDoNotLeakFromTheLocale() {
        java.util.Locale.setDefault(java.util.Locale.GERMANY)
        try {
            assertTrue(ParkingSpot(13.5, 100.25, "", 0).geoUri().startsWith("geo:13.500000,100.250000"))
        } finally {
            java.util.Locale.setDefault(java.util.Locale.US)
        }
    }

    @Test fun placesMustBeOnEarth() {
        assertTrue(ParkingSpot.isValid(13.75, 100.5))
        assertFalse(ParkingSpot.isValid(91.0, 0.0))
        assertFalse(ParkingSpot.isValid(0.0, 181.0))
        assertFalse(ParkingSpot.isValid(0.0, 0.0)) // what a provider reports when it has no fix
    }
}
