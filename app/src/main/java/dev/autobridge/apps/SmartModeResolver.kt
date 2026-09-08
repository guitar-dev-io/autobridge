package dev.autobridge.apps

import dev.autobridge.core.model.AppProfile
import dev.autobridge.core.model.AudioMode

/** Mode selected for an app before Android Auto/car UI orchestration begins. */
enum class SmartMode {
    MEDIA,
    MIRROR,
    BROWSER,
    NATIVE_CAR
}

data class SmartModeDecision(
    val mode: SmartMode,
    val reason: String
)

/** Pure resolver so app behavior is testable and not hardcoded in a screen class. */
object SmartModeResolver {
    fun resolve(packageName: String, profile: AppProfile): SmartModeDecision {
        val normalized = packageName.lowercase()
        return when {
            normalized == "com.netflix.mediaclient" ->
                SmartModeDecision(
                    SmartMode.MIRROR,
                    "protected video app; MediaProjection may blank DRM content"
                )
            normalized == "com.spotify.music" || normalized.contains("podcast") || normalized.contains("music") ->
                SmartModeDecision(SmartMode.MEDIA, "audio-first package")
            profile.audioMode == AudioMode.MEDIA && !profile.autoMirror ->
                SmartModeDecision(SmartMode.MEDIA, "profile prefers native MediaSession")
            normalized == "com.android.chrome" ||
                normalized == "com.google.android.apps.chrome" ||
                normalized.contains("browser") ->
                SmartModeDecision(
                    if (profile.autoMirror) SmartMode.MIRROR else SmartMode.BROWSER,
                    if (profile.autoMirror) "profile requests mirrored browser" else "browser package"
                )
            normalized == "com.google.android.youtube" || normalized.contains("video") ->
                SmartModeDecision(SmartMode.MIRROR, "video package uses parked mirror")
            normalized == "com.google.android.apps.maps" || normalized.contains("maps") || normalized.contains("navigation") ->
                SmartModeDecision(
                    if (profile.autoMirror) SmartMode.MIRROR else SmartMode.NATIVE_CAR,
                    if (profile.autoMirror) "map profile requests mirror" else "native navigation preference"
                )
            profile.autoMirror -> SmartModeDecision(SmartMode.MIRROR, "profile requests mirror")
            else -> SmartModeDecision(SmartMode.NATIVE_CAR, "no mirror or media preference")
        }
    }
}
