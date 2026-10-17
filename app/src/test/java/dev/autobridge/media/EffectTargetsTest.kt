package dev.autobridge.media

import org.junit.Assert.assertEquals
import org.junit.Test

class EffectTargetsTest {
    private val owners = mapOf(AudioEq.OWNER_PLAYER to 41, "app:com.spotify.music" to 97, "app:broken" to 0)

    @Test fun appScopeKeepsToOurOwnPlayer() {
        assertEquals(setOf(41), EffectTargets.sessions(EffectScope.APP, owners))
    }

    @Test fun musicAppsAddsEveryAnnouncedSessionButNotAnInvalidOne() {
        assertEquals(setOf(41, 97), EffectTargets.sessions(EffectScope.MUSIC_APPS, owners))
    }

    @Test fun allSoundIsTheOutputMixAloneSoNothingIsEqualisedTwice() {
        assertEquals(setOf(0), EffectTargets.sessions(EffectScope.ALL, owners))
        assertEquals(setOf(0), EffectTargets.sessions(EffectScope.ALL, emptyMap()))
    }
}
