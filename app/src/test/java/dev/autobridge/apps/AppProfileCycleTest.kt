package dev.autobridge.apps

import dev.autobridge.core.model.AppProfile
import dev.autobridge.core.model.AudioMode
import dev.autobridge.core.model.ResolutionPreset
import dev.autobridge.core.model.ScaleMode
import org.junit.Assert.assertEquals
import org.junit.Test

class AppProfileCycleTest {
    private val base = AppProfile(packageName = "com.example")

    /** Taps [times] in a row starting from [start], returning every value seen after each tap. */
    private fun <T> walk(start: AppProfile, times: Int, step: (AppProfile) -> AppProfile, read: (AppProfile) -> T): List<T> {
        var current = start
        return List(times) { current = step(current); read(current) }
    }

    @Test fun scaleVisitsEveryModeAndReturnsToFit() {
        assertEquals(
            listOf(ScaleMode.FILL, ScaleMode.STRETCH, ScaleMode.ONE_TO_ONE, ScaleMode.FIT),
            walk(base.copy(scaleMode = ScaleMode.FIT), 4, AppProfileCycle::nextScale) { it.scaleMode }
        )
    }

    @Test fun resolutionCyclesThroughPresetsAndBackToAuto() {
        assertEquals(
            listOf(ResolutionPreset.HD_720P, ResolutionPreset.FULL_HD_1080P, null),
            walk(base.copy(preferredResolution = null), 3, AppProfileCycle::nextResolution) { it.preferredResolution }
        )
    }

    @Test fun storedAutoAndCustomResolutionsDoNotStick() {
        assertEquals(ResolutionPreset.HD_720P, AppProfileCycle.nextResolution(base.copy(preferredResolution = ResolutionPreset.AUTO)).preferredResolution)
        assertEquals(null, AppProfileCycle.nextResolution(base.copy(preferredResolution = ResolutionPreset.CUSTOM)).preferredResolution)
    }

    @Test fun fpsCyclesSystemThirtySixty() {
        assertEquals(
            listOf(30, 60, null),
            walk(base.copy(preferredFps = null), 3, AppProfileCycle::nextFps) { it.preferredFps }
        )
    }

    @Test fun anUnlistedFpsFallsBackToSystem() {
        assertEquals(null, AppProfileCycle.nextFps(base.copy(preferredFps = 24)).preferredFps)
    }

    @Test fun audioVisitsEveryMode() {
        assertEquals(
            listOf(AudioMode.MIRROR, AudioMode.OFF, AudioMode.MEDIA),
            walk(base.copy(audioMode = AudioMode.MEDIA), 3, AppProfileCycle::nextAudio) { it.audioMode }
        )
    }

    @Test fun eachCycleChangesOnlyItsOwnField() {
        val profile = base.copy(scaleMode = ScaleMode.FILL, preferredFps = 30, audioMode = AudioMode.OFF)
        assertEquals(profile.copy(scaleMode = ScaleMode.STRETCH), AppProfileCycle.nextScale(profile))
        assertEquals(profile.copy(preferredFps = 60), AppProfileCycle.nextFps(profile))
        assertEquals(profile.copy(audioMode = AudioMode.MEDIA), AppProfileCycle.nextAudio(profile))
        assertEquals(profile.copy(preferredResolution = ResolutionPreset.HD_720P), AppProfileCycle.nextResolution(profile))
    }
}
