package dev.autobridge.apps

import dev.autobridge.core.model.AppProfile
import dev.autobridge.core.model.AudioMode
import dev.autobridge.core.model.ResolutionPreset
import dev.autobridge.core.model.ScaleMode

/**
 * The next value each tap on a per-app profile row moves to.
 *
 * These lived as private functions in `MainActivity` and were emptied by an unrelated rework, which
 * left four rows on the profile screen that showed a value and did nothing when tapped. Keeping the
 * order here, pure, means the rows cannot silently stop cycling again without a JVM test failing.
 */
object AppProfileCycle {

    fun nextScale(profile: AppProfile): AppProfile = profile.copy(
        scaleMode = when (profile.scaleMode) {
            ScaleMode.FIT -> ScaleMode.FILL
            ScaleMode.FILL -> ScaleMode.STRETCH
            ScaleMode.STRETCH -> ScaleMode.ONE_TO_ONE
            ScaleMode.ONE_TO_ONE -> ScaleMode.FIT
        }
    )

    /** AUTO is stored as null, so a profile that never chose a resolution reads the same as one reset to it. */
    fun nextResolution(profile: AppProfile): AppProfile = profile.copy(
        preferredResolution = when (profile.preferredResolution) {
            null, ResolutionPreset.AUTO -> ResolutionPreset.HD_720P
            ResolutionPreset.HD_720P -> ResolutionPreset.FULL_HD_1080P
            ResolutionPreset.FULL_HD_1080P, ResolutionPreset.CUSTOM -> null
        }
    )

    /** null is "system": the renderer falls back to the vehicle profile's rate. */
    fun nextFps(profile: AppProfile): AppProfile = profile.copy(
        preferredFps = when (profile.preferredFps) {
            null -> 30
            30 -> 60
            else -> null
        }
    )

    fun nextAudio(profile: AppProfile): AppProfile = profile.copy(
        audioMode = when (profile.audioMode) {
            AudioMode.MEDIA -> AudioMode.MIRROR
            AudioMode.MIRROR -> AudioMode.OFF
            AudioMode.OFF -> AudioMode.MEDIA
        }
    )
}
