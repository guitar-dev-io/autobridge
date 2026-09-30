package dev.autobridge.settings

import android.content.Context
import androidx.core.content.edit

/**
 * Whether the user has read and accepted the video-viewing safety warning
 * ([dev.autobridge.car.CarVideoDisclaimerScreen]) at least once.
 *
 * This gates the WARNING, not the feature: it never blocks Video/Streaming access on its own (that
 * remains [dev.autobridge.core.policy.FeaturePolicy] + [dev.autobridge.safety.SafetyEnforcement]).
 * It only ensures the driver has explicitly acknowledged the risk before the first video plays.
 */
object VideoDisclaimerStore {
    private const val PREFS_NAME = "autobridge_app_prefs"
    private const val KEY_ACCEPTED = "video_warning_accepted"

    fun isAccepted(context: Context): Boolean = prefs(context).getBoolean(KEY_ACCEPTED, false)

    fun setAccepted(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY_ACCEPTED, value) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
