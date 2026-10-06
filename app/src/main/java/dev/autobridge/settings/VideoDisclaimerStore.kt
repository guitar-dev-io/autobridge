package dev.autobridge.settings

import android.content.Context
import androidx.core.content.edit

/**
 * Whether the user has read and accepted the video-viewing safety warning
 * ([dev.autobridge.car.CarVideoDisclaimerScreen]) recently enough: see [VideoDisclaimerPolicy] for
 * when an acceptance lapses.
 *
 * This gates the WARNING, not the feature: it never blocks Video/Streaming access on its own (that
 * remains [dev.autobridge.core.policy.FeaturePolicy] + [dev.autobridge.safety.SafetyEnforcement]).
 * It only ensures the driver has explicitly acknowledged the risk before video plays.
 */
object VideoDisclaimerStore {
    private const val PREFS_NAME = "autobridge_app_prefs"

    // The old boolean ("video_warning_accepted") is no longer read: an acceptance from before the
    // warning could expire carries no date, so it is asked again once.
    private const val KEY_ACCEPTED_AT = "video_warning_accepted_at"
    private const val KEY_VERSION = "video_warning_version"

    fun isAccepted(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val prefs = prefs(context)
        return VideoDisclaimerPolicy.stands(prefs.getLong(KEY_ACCEPTED_AT, 0L), prefs.getInt(KEY_VERSION, 0), nowMs)
    }

    fun setAccepted(context: Context, value: Boolean, nowMs: Long = System.currentTimeMillis()) {
        prefs(context).edit {
            if (value) {
                putLong(KEY_ACCEPTED_AT, nowMs)
                putInt(KEY_VERSION, VideoDisclaimerPolicy.VERSION)
            } else {
                remove(KEY_ACCEPTED_AT)
                remove(KEY_VERSION)
            }
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
