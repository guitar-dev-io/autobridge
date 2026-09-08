package dev.autobridge.core.model

/** Build-time operating mode shared by every AutoBridge feature. */
enum class AutoBridgeMode {
    SAFE,
    PERSONAL,
    LAB;

    companion object {
        fun fromBuildValue(value: String?): AutoBridgeMode =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: SAFE
    }
}

/** Runtime environment used to determine which development controls are safe to expose. */
enum class Environment {
    DHU,
    EMULATOR,
    TEST_BENCH,
    REAL_CAR
}

/** Fail-closed vehicle movement state. */
enum class VehicleState {
    PARKED,
    MOVING,
    UNKNOWN
}

/** Product capabilities. Availability is resolved centrally by FeaturePolicy. */
enum class Feature {
    MIRROR,
    TOUCH,
    QUICK_APPS,
    APP_LAUNCHER,
    MEDIA,
    BROWSER,
    VIDEO,
    AUDIO_CAPTURE,
    SCREEN_OFF,
    SHIZUKU,
    DEVELOPER,
    DEBUG_OVERLAY
}

enum class ScaleMode {
    FIT,
    FILL,
    STRETCH,
    ONE_TO_ONE
}

enum class RotationMode {
    AUTO,
    PHONE,
    PORTRAIT,
    LANDSCAPE
}

enum class AudioMode {
    MEDIA,
    MIRROR,
    OFF
}

enum class ResolutionPreset {
    AUTO,
    HD_720P,
    FULL_HD_1080P,
    CUSTOM
}

data class Size(val width: Int, val height: Int) {
    val isValid: Boolean get() = width > 0 && height > 0
}

data class Insets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
) {
    companion object {
        val ZERO = Insets()
    }
}

data class VehicleProfile(
    val id: String,
    val name: String,
    val carResolution: Size?,
    val safeInsets: Insets,
    val touchOffsetX: Float,
    val touchOffsetY: Float,
    val preferredFps: Int?,
    val defaultScale: ScaleMode
)

data class AppProfile(
    val packageName: String,
    val profileId: String = "default",
    val autoMirror: Boolean = false,
    val autoFullscreen: Boolean = false,
    val rotationMode: RotationMode = RotationMode.AUTO,
    val scaleMode: ScaleMode = ScaleMode.FIT,
    val preferredFps: Int? = null,
    val preferredResolution: ResolutionPreset? = null,
    val touchEnabled: Boolean = true,
    val audioMode: AudioMode = AudioMode.MIRROR,
    val keepPhoneScreenOn: Boolean = true
)

data class RuntimeContext(
    val mode: AutoBridgeMode,
    val environment: Environment,
    val vehicleState: VehicleState,
    val connected: Boolean = false,
    val vehicleProfile: VehicleProfile? = null,
    val currentPackageName: String? = null,
    val currentFeature: Feature? = null,
    val scaleMode: ScaleMode = ScaleMode.FIT,
    val rotationMode: RotationMode = RotationMode.AUTO,
    val audioMode: AudioMode = AudioMode.MEDIA,
    val fullscreen: Boolean = false,
    val sessionStartedAtElapsedMs: Long? = null
)
