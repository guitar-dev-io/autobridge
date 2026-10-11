package dev.autobridge.car

import dev.autobridge.car.NowPlayingChoice.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingChoiceTest {
    private fun choose(web: Boolean = false, bridgeLive: Boolean = false, session: Boolean = false, snapshot: Boolean = false) =
        NowPlayingChoice.choose(web, bridgeLive, session, snapshot)

    /** The bug: radio on, an old Send-to-car item saved, and the card showed the old item. */
    @Test fun aPlayingSessionBeatsAnOldBridgeSnapshot() {
        assertEquals(Source.SESSION, choose(session = true, snapshot = true))
    }

    @Test fun theBrowserPageWinsBecauseItIsWhatIsAudible() {
        assertEquals(Source.WEB, choose(web = true, bridgeLive = true, session = true, snapshot = true))
    }

    /** A bridge source plays natively through the same session; its card keeps resume and artwork. */
    @Test fun aLiveBridgeSourceBeatsTheSession() {
        assertEquals(Source.BRIDGE_LIVE, choose(bridgeLive = true, session = true, snapshot = true))
    }

    @Test fun withNothingPlayingTheSnapshotIsStillOffered() {
        assertEquals(Source.BRIDGE_SNAPSHOT, choose(snapshot = true))
        assertEquals(Source.NONE, choose())
    }

    @Test fun aSessionCountsWhenPlayingOrLoadedAndPaused() {
        assertTrue(NowPlayingChoice.sessionActive(isPlaying = true, itemCount = 1, playbackState = NowPlayingChoice.STATE_READY))
        assertTrue("paused but loaded", NowPlayingChoice.sessionActive(false, 1, NowPlayingChoice.STATE_READY))
        assertTrue("tuning a live channel", NowPlayingChoice.sessionActive(false, 1, NowPlayingChoice.STATE_BUFFERING))
    }

    @Test fun anEmptyIdleOrEndedSessionDoesNotCount() {
        assertFalse(NowPlayingChoice.sessionActive(isPlaying = false, itemCount = 0, playbackState = NowPlayingChoice.STATE_READY))
        assertFalse("idle", NowPlayingChoice.sessionActive(false, 1, 1))
        assertFalse("ended", NowPlayingChoice.sessionActive(false, 1, 4))
    }

    /** Guards the mirrored constants against Media3 renumbering them. */
    @Test fun theStateConstantsMatchMedia3() {
        assertEquals(androidx.media3.common.Player.STATE_BUFFERING, NowPlayingChoice.STATE_BUFFERING)
        assertEquals(androidx.media3.common.Player.STATE_READY, NowPlayingChoice.STATE_READY)
    }
}
