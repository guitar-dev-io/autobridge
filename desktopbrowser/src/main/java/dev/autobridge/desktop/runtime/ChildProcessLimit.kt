package dev.autobridge.desktop.runtime

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Android 12+ "phantom process" limit: the system kills an app's child processes once all apps
 * together have more than 32 of them, and kills any that use a lot of CPU in the background.
 * A desktop browser runs 15–25 processes on its own, so on a phone with the limit in force the
 * browser gets killed at random (observed on the emulator: every runtime process is tracked as
 * a `PhantomProcessRecord`).
 *
 * The limit is the user's to lift; AutoBridge never changes it. Android 14+ has a switch for it
 * (Developer options › "Disable child process restrictions", stored as
 * `settings_enable_monitor_phantom_procs`); Android 12 and 13 need the one adb command below.
 */
object ChildProcessLimit {
    /** Same command Termux documents for the same problem. */
    const val ADB_COMMAND =
        "adb shell \"/system/bin/device_config set_sync_disabled_for_tests persistent; " +
            "/system/bin/device_config put activity_manager max_phantom_processes 2147483647\""

    enum class Status { NOT_APPLICABLE, LIFTED, ACTIVE, UNKNOWN }

    fun status(context: Context): Status {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return Status.NOT_APPLICABLE
        val monitor = runCatching {
            Settings.Global.getString(context.contentResolver, "settings_enable_monitor_phantom_procs")
        }.getOrNull()
        return when {
            monitor == "false" -> Status.LIFTED
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> Status.ACTIVE
            // Android 12/13: the adb override is not readable by apps, so it cannot be confirmed.
            else -> Status.UNKNOWN
        }
    }
}
