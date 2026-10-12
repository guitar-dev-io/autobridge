package dev.autobridge.car

import dev.autobridge.logging.StructuredLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CarDisplayInfoTest {
    @Before fun setUp() {
        CarDisplayInfo.reset()
        StructuredLog.clear()
    }

    @After fun tearDown() {
        CarDisplayInfo.reset()
        StructuredLog.clear()
    }

    private fun logged() = StructuredLog.recent().filter { it.tag == "CAR_DISPLAY" }.map { it.message }

    @Test fun aZeroOrNegativeSurfaceIsIgnored() {
        assertFalse(CarDisplayInfo.record(0, 480, 160))
        assertFalse(CarDisplayInfo.record(800, -1, 160))
        assertNull(CarDisplayInfo.last)
        assertTrue(logged().isEmpty())
    }

    @Test fun theSameSurfaceFromAnotherScreenIsLoggedOnce() {
        assertTrue(CarDisplayInfo.record(800, 1280, 160, source = "home"))
        assertFalse(CarDisplayInfo.record(800, 1280, 160, source = "browser"))
        assertEquals(listOf("surface=800x1280@160 stable=none visible=none via=home"), logged())
    }

    @Test fun aChangedDpiIsANewFingerprint() {
        CarDisplayInfo.record(800, 1280, 160)
        assertTrue(CarDisplayInfo.record(800, 1280, 240))
        assertEquals(CarDisplayInfo.Surface(800, 1280, 240), CarDisplayInfo.last)
        assertEquals(2, logged().size)
    }

    @Test fun areasJoinTheSurfaceInOneLine() {
        CarDisplayInfo.record(800, 1280, 160, source = "home")
        CarDisplayInfo.recordStableArea(0, 80, 800, 1280, source = "home")
        CarDisplayInfo.recordVisibleArea(0, 80, 800, 1200, source = "home")
        assertEquals(
            "surface=800x1280@160 stable=[0,80][800,1280] visible=[0,80][800,1200] via=home",
            logged().last()
        )
    }

    @Test fun anAreaReportedBeforeTheSurfaceIsKept() {
        CarDisplayInfo.recordStableArea(0, 80, 800, 1280)
        CarDisplayInfo.record(800, 1280, 160)
        assertEquals(CarDisplayInfo.Area(0, 80, 800, 1280), CarDisplayInfo.fingerprint.stable)
    }

    @Test fun aRepeatedAreaDoesNotLogAgain() {
        CarDisplayInfo.recordVisibleArea(0, 0, 800, 1280)
        assertFalse(CarDisplayInfo.recordVisibleArea(0, 0, 800, 1280))
        assertEquals(1, logged().size)
    }

    @Test fun resetStartsAFreshFingerprintThatLogsAgain() {
        CarDisplayInfo.record(800, 480, 160)
        CarDisplayInfo.reset()
        assertNull(CarDisplayInfo.last)
        assertTrue(CarDisplayInfo.record(800, 480, 160))
        assertEquals(2, logged().size)
    }

    @Test fun lowestStreamStillRecognisedThroughTheFingerprint() {
        CarDisplayInfo.record(800, 400, 160)
        assertTrue(CarDisplayInfo.isLowestStream(CarDisplayInfo.last!!))
    }
}
