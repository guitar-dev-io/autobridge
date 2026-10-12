package dev.autobridge.display

import dev.autobridge.core.model.Insets
import dev.autobridge.settings.SettingsCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceProfileTest {

    /**
     * An unverified profile must not change anything: its numbers would be guesses. When a profile
     * is measured, its values move and its flag flips together, and this test is what forces that.
     */
    @Test fun anUnverifiedProfileBehavesExactlyLikeDefault() {
        SurfaceProfile.entries.filterNot { it.hardwareValidated }.forEach { profile ->
            assertEquals(profile.name, SurfaceProfile.DEFAULT.fallbackDpi, profile.fallbackDpi)
            assertEquals(profile.name, SurfaceProfile.DEFAULT.safeInsets, profile.safeInsets)
            assertEquals(profile.name, SurfaceProfile.DEFAULT.touchOffsetX, profile.touchOffsetX, 0f)
            assertEquals(profile.name, SurfaceProfile.DEFAULT.touchOffsetY, profile.touchOffsetY, 0f)
            assertEquals(profile.name, SurfaceProfile.DEFAULT.preferredFps, profile.preferredFps)
        }
    }

    /** DEFAULT adds nothing on top of what the host reports; this is what the mirror used before the merge. */
    @Test fun defaultAddsNothingToTheHostGeometry() {
        assertEquals(Insets.ZERO, SurfaceProfile.DEFAULT.safeInsets)
        assertEquals(0f, SurfaceProfile.DEFAULT.touchOffsetX, 0f)
        assertEquals(0f, SurfaceProfile.DEFAULT.touchOffsetY, 0f)
        assertNull(SurfaceProfile.DEFAULT.preferredFps)
        assertTrue(SurfaceProfile.DEFAULT.hardwareValidated)
    }

    /** Stored by enum name; a choice saved before the merge must still load. */
    @Test fun previouslyStoredChoicesStillLoad() {
        assertEquals(SurfaceProfile.FORD_NEXT_GEN, SettingsCodec.surfaceProfile("FORD_NEXT_GEN"))
        assertEquals(SurfaceProfile.DEFAULT, SettingsCodec.surfaceProfile("DEFAULT"))
        // The removed VehicleProfiles ids were never persisted, but an unknown name must not crash.
        assertEquals(SurfaceProfile.DEFAULT, SettingsCodec.surfaceProfile("GENERIC_ANDROID_AUTO"))
        assertEquals(SurfaceProfile.DEFAULT, SettingsCodec.surfaceProfile("DHU_TEST"))
    }
}
