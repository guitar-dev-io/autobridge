package dev.autobridge.parking

import org.junit.Assert.assertEquals
import org.junit.Test

class ParkingPhotoTest {
    @Test fun aSmallPictureIsNotShrunk() {
        assertEquals(1, ParkingPhoto.sampleSize(1200, 800, 1600))
        assertEquals(1, ParkingPhoto.sampleSize(1600, 900, 1600))
    }

    @Test fun aBigCameraPictureIsHalvedUntilItFits() {
        // 4000 x 3000 halves to 2000 (still 1600 or more) and no further: 1000 would be too soft.
        assertEquals(2, ParkingPhoto.sampleSize(4000, 3000, 1600))
        assertEquals(4, ParkingPhoto.sampleSize(8000, 6000, 1600))
        assertEquals(4, ParkingPhoto.sampleSize(3000, 8000, 1600))
    }

    @Test fun theExifTurnsAreRead() {
        assertEquals(0, ParkingPhoto.rotationDegrees(1))
        assertEquals(90, ParkingPhoto.rotationDegrees(6))
        assertEquals(180, ParkingPhoto.rotationDegrees(3))
        assertEquals(270, ParkingPhoto.rotationDegrees(8))
        assertEquals(0, ParkingPhoto.rotationDegrees(0))
    }
}
