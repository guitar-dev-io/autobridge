package dev.autobridge.settings

import android.content.Context
import androidx.core.content.edit
import dev.autobridge.display.ScreenOffController
import dev.autobridge.display.SurfaceProfile

/**
 * App-wide preferences that must survive process death and app restarts, following the same
 * SharedPreferences `object` pattern as `FavoriteAppsStore`/`PerAppProfileStore`.
 *
 * These settings are held at runtime as `@Volatile var`s on their owning singletons
 * ([SurfaceProfile.active], [ScreenOffController.pipelineMode], and [MirrorSettings] fields) so
 * the hot paths don't touch disk. This store is the durable backing: [persist*] writes on change,
 * and [restore] reloads on startup. The enum<->name mapping ([SettingsCodec]) is pulled out as
 * pure logic so it's unit-testable without a real [Context], and so an unknown/renamed persisted
 * value falls back safely instead of throwing.
 */
object SettingsStore {
    private const val PREFS_NAME = "autobridge_settings"
    private const val KEY_SURFACE_PROFILE = "surface_profile"
    private const val KEY_SCREEN_OFF_MODE = "screen_off_pipeline_mode"
    private const val KEY_PREVENT_SCREEN_SLEEP = "prevent_screen_sleep"
    private const val KEY_AUTO_DIM_DELAY_SECONDS = "auto_dim_delay_seconds"
    private const val KEY_STOP_ON_DISCONNECT = "stop_on_disconnect"
    private const val KEY_AUTO_LAUNCH_LAST_APP = "auto_launch_last_app"
    private const val KEY_AUTO_START_MIRROR = "auto_start_mirror"

    /** Applies every persisted setting to its runtime owner. Call once early (e.g. Activity.onCreate). */
    fun restore(context: Context) {
        val prefs = prefs(context)
        SurfaceProfile.active = SettingsCodec.surfaceProfile(prefs.getString(KEY_SURFACE_PROFILE, null))
        ScreenOffController.pipelineMode =
            SettingsCodec.screenOffMode(prefs.getString(KEY_SCREEN_OFF_MODE, null))
        MirrorSettings.preventScreenSleep = prefs.getBoolean(KEY_PREVENT_SCREEN_SLEEP, false)
        MirrorSettings.autoDimDelay =
            AutoDimDelay.fromSeconds(prefs.getInt(KEY_AUTO_DIM_DELAY_SECONDS, 0))
        MirrorSettings.stopOnDisconnect = prefs.getBoolean(KEY_STOP_ON_DISCONNECT, false)
        MirrorSettings.autoLaunchLastApp = prefs.getBoolean(KEY_AUTO_LAUNCH_LAST_APP, false)
        MirrorSettings.autoStartMirror = prefs.getBoolean(KEY_AUTO_START_MIRROR, false)
    }

    fun persistSurfaceProfile(context: Context, profile: SurfaceProfile) {
        prefs(context).edit { putString(KEY_SURFACE_PROFILE, profile.name) }
    }

    fun persistScreenOffMode(context: Context, mode: ScreenOffController.PipelineMode) {
        prefs(context).edit { putString(KEY_SCREEN_OFF_MODE, mode.name) }
    }

    fun persistPreventScreenSleep(context: Context, enabled: Boolean) {
        MirrorSettings.preventScreenSleep = enabled
        prefs(context).edit { putBoolean(KEY_PREVENT_SCREEN_SLEEP, enabled) }
    }

    fun persistAutoDimDelay(context: Context, delay: AutoDimDelay) {
        MirrorSettings.autoDimDelay = delay
        prefs(context).edit { putInt(KEY_AUTO_DIM_DELAY_SECONDS, delay.seconds) }
    }

    fun persistStopOnDisconnect(context: Context, enabled: Boolean) {
        MirrorSettings.stopOnDisconnect = enabled
        prefs(context).edit { putBoolean(KEY_STOP_ON_DISCONNECT, enabled) }
    }

    fun persistAutoLaunchLastApp(context: Context, enabled: Boolean) {
        MirrorSettings.autoLaunchLastApp = enabled
        prefs(context).edit { putBoolean(KEY_AUTO_LAUNCH_LAST_APP, enabled) }
    }

    fun persistAutoStartMirror(context: Context, enabled: Boolean) {
        MirrorSettings.autoStartMirror = enabled
        prefs(context).edit { putBoolean(KEY_AUTO_START_MIRROR, enabled) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * Pure, null-safe decoding of persisted enum names back to enum values, with an explicit default
 * when the stored value is absent or unrecognized (e.g. an enum constant was renamed between
 * versions). Kept separate from [SettingsStore] so it's testable without Android.
 */
object SettingsCodec {
    fun surfaceProfile(name: String?): SurfaceProfile =
        SurfaceProfile.entries.firstOrNull { it.name == name } ?: SurfaceProfile.DEFAULT

    fun screenOffMode(name: String?): ScreenOffController.PipelineMode =
        ScreenOffController.PipelineMode.entries.firstOrNull { it.name == name }
            ?: ScreenOffController.PipelineMode.AUTO_MIRROR
}
