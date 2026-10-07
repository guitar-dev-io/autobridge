package dev.autobridge.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The media-card suppression lever decomposes to one pure predicate,
 * [MediaPlaybackService.shouldSurfaceSession], which [MediaPlaybackService.onGetSession] routes
 * through. Asserting the predicate isolates the Duo gate from media3's service machinery while
 * pinning the exact null-vs-session decision. The predicate reads no instance state, so a bare
 * service instance is enough to reach it (the CarScreenPowerTest pure-decision style).
 */
class MediaSessionDuoGateTest {
    private val service = MediaPlaybackService()

    @Test fun duoActiveNeverSurfacesRegardlessOfAuthorization() {
        // FR2 / AC1 (Duo-then-play): with Duo live, the host is never handed the session.
        assertFalse(service.shouldSurfaceSession(duoActive = true, authorized = true))
        assertFalse(service.shouldSurfaceSession(duoActive = true, authorized = false))
    }

    @Test fun duoInactiveAndAuthorizedSurfaces() {
        // FR4 / AC3 / AC4: the normal media/mirror route is unchanged when Duo is not active.
        assertTrue(service.shouldSurfaceSession(duoActive = false, authorized = true))
    }

    @Test fun duoInactiveAndUnauthorizedDoesNotSurface() {
        // Existing authorization behaviour preserved: an unauthorized controller is still denied.
        assertFalse(service.shouldSurfaceSession(duoActive = false, authorized = false))
    }
}
