package dev.autobridge.apps

import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.Surface
import dev.autobridge.core.model.RotationMode

/** Owns temporary per-app rotation writes and restores the exact phone settings on cleanup. */
object AppRotationController {
    private const val TAG = "AutoBridgeAppRotation"

    private data class Snapshot(val accelerometer: Int, val userRotation: Int)

    private var snapshot: Snapshot? = null

    @Synchronized
    fun apply(context: Context, mode: RotationMode): Boolean {
        if (mode != RotationMode.LANDSCAPE) {
            restore(context)
            return true
        }
        if (!Settings.System.canWrite(context)) return false
        if (snapshot == null) {
            val resolver = context.contentResolver
            snapshot = runCatching {
                Snapshot(
                    Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION),
                    Settings.System.getInt(resolver, Settings.System.USER_ROTATION)
                )
            }.getOrElse {
                Log.w(TAG, "Could not snapshot phone rotation", it)
                return false
            }
        }
        val resolver = context.contentResolver
        val applied = runCatching {
            check(Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0))
            check(Settings.System.putInt(resolver, Settings.System.USER_ROTATION, Surface.ROTATION_90))
        }.isSuccess
        if (!applied) Log.w(TAG, "Could not apply per-app landscape rotation")
        return applied
    }

    @Synchronized
    fun restore(context: Context) {
        val previous = snapshot ?: return
        snapshot = null
        if (!Settings.System.canWrite(context)) {
            Log.w(TAG, "Cannot restore per-app rotation without WRITE_SETTINGS")
            return
        }
        val resolver = context.contentResolver
        runCatching {
            check(Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, previous.accelerometer))
            check(Settings.System.putInt(resolver, Settings.System.USER_ROTATION, previous.userRotation))
        }.onFailure { Log.w(TAG, "Could not restore per-app rotation", it) }
    }
}
