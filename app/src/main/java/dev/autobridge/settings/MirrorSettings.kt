package dev.autobridge.settings

/** User-facing automation knobs inspired by ScreenOnAuto's documented feature set. */
enum class AutoDimDelay(val seconds: Int, val label: String) {
    OFF(0, "Off"),
    SECONDS_15(15, "15 seconds"),
    SECONDS_30(30, "30 seconds"),
    SECONDS_60(60, "60 seconds"),
    SECONDS_120(120, "120 seconds");

    companion object {
        fun fromSeconds(seconds: Int): AutoDimDelay =
            entries.firstOrNull { it.seconds == seconds } ?: OFF
    }

    fun next(): AutoDimDelay = entries[(ordinal + 1) % entries.size]
}

/**
 * Runtime mirror settings. [SettingsStore] is the durable backing store; keeping these values in
 * memory means projection and car callbacks do not perform disk I/O on their hot paths.
 */
object MirrorSettings {
    @Volatile
    var preventScreenSleep: Boolean = false

    @Volatile
    var autoDimDelay: AutoDimDelay = AutoDimDelay.OFF

    /** Opt-in ScreenOnAuto-style panel-only power-off when auto-dim fires. */
    @Volatile
    var screenOffOnAutoDim: Boolean = false

    /** Opt-in privileged pointer injection when a raw pointer source is available. */
    @Volatile
    var realTouchEnabled: Boolean = false

    @Volatile
    var stopOnDisconnect: Boolean = false

    @Volatile
    var autoLaunchLastApp: Boolean = false

    @Volatile
    var autoStartMirror: Boolean = false

    /** Active per-app FPS target; null falls back to the vehicle profile/default renderer rate. */
    @Volatile
    var preferredFps: Int? = null
}
