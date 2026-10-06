package dev.autobridge.checkpoint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CheckpointMathTest {
    private fun point(id: Long, lat: Double, lon: Double) = Checkpoint(id, "p$id", lat, lon, 0)

    @Test fun oneDegreeOfLatitudeIsAbout111Km() {
        assertEquals(111_195.0, CheckpointMath.distanceM(13.0, 100.0, 14.0, 100.0), 200.0)
    }

    @Test fun samePlaceIsZero() {
        assertEquals(0.0, CheckpointMath.distanceM(13.75, 100.5, 13.75, 100.5), 0.001)
    }

    @Test fun nearestFirstAndLimited() {
        val points = listOf(point(1, 13.80, 100.5), point(2, 13.751, 100.5), point(3, 13.76, 100.5), point(4, 14.5, 100.5))
        val near = CheckpointMath.nearest(points, 13.75, 100.5, limit = 2)
        assertEquals(listOf(2L, 3L), near.map { it.checkpoint.id })
        assertEquals(111.0, near[0].meters, 5.0)
    }

    @Test fun coordinatesFromACommaPair() {
        assertEquals(13.7563 to 100.5018, CheckpointMath.parseCoordinates("13.7563, 100.5018"))
    }

    @Test fun coordinatesFromAMapLink() {
        assertEquals(13.7563 to 100.5018, CheckpointMath.parseCoordinates("https://www.google.com/maps/@13.7563,100.5018,15z"))
    }

    @Test fun outOfRangeOrMissingIsNull() {
        assertNull(CheckpointMath.parseCoordinates("no numbers here"))
        assertNull(CheckpointMath.parseCoordinates("123.4, 200.5"))
    }
}
