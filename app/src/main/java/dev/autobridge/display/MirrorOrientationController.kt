package dev.autobridge.display

import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.Surface

/**
 * Optionally locks the phone's default display to landscape for the duration of a mirror
 * projection. This keeps the existing zero-copy AUTO_MIRROR/FIT path, but gives the car surface
 * a landscape source so a 2:1 DHU surface is used much more efficiently.
 *
 * The setting is deliberately opt-in because rotation settings are global to the phone. The
 * exact values are captured once when this feature is applied and restored when the projection
 * ends. This controller never changes parking state or any input/render safety gate.
 */
object MirrorOrientationController {
    private const val TAG = "AutoBridgeMirrorRotation"

    private data class RotationSnapshot(
        val accelerometerRotation: Int,
        val userRotation: Int
    )

    private var projectionActive = false
    private var snapshot: RotationSnapshot? = null

    @Volatile
    var isEnabled: Boolean = false
        private set

    /** True when the landscape lock is currently applied to an active projection. */
    val isApplied: Boolean
        @Synchronized get() = snapshot != null

    fun hasPermission(context: Context): Boolean = Settings.System.canWrite(context)

    /**
     * Changes the process-local preference. Enabling requires WRITE_SETTINGS; disabling also
     * restores the captured values immediately when a projection is active.
     */
    @Synchronized
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        if (enabled) {
            if (!hasPermission(context)) return false

            val previous = isEnabled
            isEnabled = true
            if (projectionActive && !applyLandscapeLock(context)) {
                isEnabled = previous
                return false
            }
            return true
        }

        isEnabled = false
        if (projectionActive) restoreSnapshot(context)
        return true
    }

    /** Called after a new MediaProjection session has been created. */
    @Synchronized
    fun onProjectionStarted(context: Context) {
        projectionActive = true
        if (!isEnabled) return
        if (!hasPermission(context)) {
            Log.w(TAG, "Landscape mirror enabled but WRITE_SETTINGS is no longer granted")
            return
        }
        if (!applyLandscapeLock(context)) {
            Log.w(TAG, "Could not apply landscape mirror lock; continuing without rotation change")
        }
    }

    /** Called on every projection stop/error path; safe to invoke more than once. */
    @Synchronized
    fun onProjectionStopped(context: Context) {
        projectionActive = false
        restoreSnapshot(context)
    }

    @Synchronized
    private fun applyLandscapeLock(context: Context): Boolean {
        if (snapshot != null) return true
        if (!hasPermission(context)) return false

        val resolver = context.contentResolver
        val previous = runCatching {
            RotationSnapshot(
                accelerometerRotation = Settings.System.getInt(
                    resolver,
                    Settings.System.ACCELEROMETER_ROTATION
                ),
                userRotation = Settings.System.getInt(resolver, Settings.System.USER_ROTATION)
            )
        }.getOrElse { error ->
            Log.w(TAG, "Could not snapshot current rotation settings", error)
            return false
        }

        val applied = runCatching {
            check(
                Settings.System.putInt(
                    resolver,
                    Settings.System.ACCELEROMETER_ROTATION,
                    0
                )
            ) { "ACCELEROMETER_ROTATION write was rejected" }
            check(
                Settings.System.putInt(
                    resolver,
                    Settings.System.USER_ROTATION,
                    Surface.ROTATION_90
                )
            ) { "USER_ROTATION write was rejected" }
        }.isSuccess

        if (!applied) {
            restoreValues(context, previous)
            Log.w(TAG, "Could not apply landscape rotation lock")
            return false
        }

        snapshot = previous
        Log.i(TAG, "Landscape mirror lock applied; previous rotation settings captured")
        return true
    }

    @Synchronized
    private fun restoreSnapshot(context: Context) {
        val previous = snapshot ?: return
        snapshot = null
        if (!hasPermission(context)) {
            Log.w(TAG, "Cannot restore rotation settings because WRITE_SETTINGS is not granted")
            return
        }

        restoreValues(context, previous)
        Log.i(TAG, "Landscape mirror lock removed; previous rotation settings restored")
    }

    private fun restoreValues(context: Context, previous: RotationSnapshot) {
        val resolver = context.contentResolver
        runCatching {
            check(
                Settings.System.putInt(
                    resolver,
                    Settings.System.ACCELEROMETER_ROTATION,
                    previous.accelerometerRotation
                )
            ) { "ACCELEROMETER_ROTATION restore was rejected" }
            check(
                Settings.System.putInt(
                    resolver,
                    Settings.System.USER_ROTATION,
                    previous.userRotation
                )
            ) { "USER_ROTATION restore was rejected" }
        }.onFailure { error ->
            Log.w(TAG, "Could not restore previous rotation settings", error)
        }
    }
}
