package dev.autobridge.profile

import dev.autobridge.core.model.Insets
import dev.autobridge.display.SurfaceProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveCarProfileTest {
    @After fun tearDown() {
        ActiveCarProfile.custom = null
        SurfaceProfile.active = SurfaceProfile.DEFAULT
    }

    @Test fun withNothingSavedInUseTheBuiltInDecides() {
        SurfaceProfile.active = SurfaceProfile.DEFAULT
        assertEquals(SurfaceProfile.DEFAULT.fallbackDpi, ActiveCarProfile.fallbackDpi)
        assertEquals(Insets.ZERO, ActiveCarProfile.safeInsets)
        assertEquals("Default", ActiveCarProfile.label)
        assertTrue(ActiveCarProfile.verified)
    }

    @Test fun aSavedProfileInUseReachesEveryValue() {
        ActiveCarProfile.custom = CarProfile(
            id = "p1", name = "My Ranger", fallbackDpi = 200, safeInsets = Insets(0, 80, 0, 0),
            touchOffsetX = 3f, touchOffsetY = -4f, preferredFps = 30
        )
        assertEquals(200, ActiveCarProfile.fallbackDpi)
        assertEquals(Insets(0, 80, 0, 0), ActiveCarProfile.safeInsets)
        assertEquals(3f, ActiveCarProfile.touchOffsetX, 0f)
        assertEquals(-4f, ActiveCarProfile.touchOffsetY, 0f)
        assertEquals(30, ActiveCarProfile.preferredFps)
        assertEquals("My Ranger", ActiveCarProfile.label)
        assertFalse("a saved profile is never verified", ActiveCarProfile.verified)
    }

    @Test fun aSavedProfileWithNoFrameRateLeavesTheRendererDefault() {
        ActiveCarProfile.custom = CarProfile(id = "p1", name = "x", preferredFps = null)
        assertNull(ActiveCarProfile.preferredFps)
    }
}
