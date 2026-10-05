package dev.autobridge.settings

import android.content.Context
import androidx.core.content.edit

/**
 * Small persistent app-level preferences for UI behavior that isn't specific to the mirror engine:
 * the driving-aware UI presentation toggles and the optional "Resume last session" setting.
 *
 * These are presentation/convenience flags only, bar one: [gpsSpeed] is the user's consent to read
 * location for the speed reading, and nothing else enables that.
 */
object AppPreferences {
    private const val PREFS_NAME = "autobridge_app_prefs"

    private const val KEY_DRIVING_AWARE_UI = "driving_aware_ui"
    private const val KEY_LARGER_CONTROLS = "driving_larger_controls"
    private const val KEY_PREFER_VOICE = "driving_prefer_voice"
    private const val KEY_REDUCE_CLUTTER = "driving_reduce_clutter"
    private const val KEY_RESUME_LAST_SESSION = "resume_last_session"
    private const val KEY_GPS_SPEED = "gps_speed"

    fun drivingAwareUi(context: Context): Boolean = prefs(context).getBoolean(KEY_DRIVING_AWARE_UI, true)
    fun setDrivingAwareUi(context: Context, value: Boolean) = put(context, KEY_DRIVING_AWARE_UI, value)

    fun largerControls(context: Context): Boolean = prefs(context).getBoolean(KEY_LARGER_CONTROLS, true)
    fun setLargerControls(context: Context, value: Boolean) = put(context, KEY_LARGER_CONTROLS, value)

    fun preferVoice(context: Context): Boolean = prefs(context).getBoolean(KEY_PREFER_VOICE, true)
    fun setPreferVoice(context: Context, value: Boolean) = put(context, KEY_PREFER_VOICE, value)

    fun reduceClutter(context: Context): Boolean = prefs(context).getBoolean(KEY_REDUCE_CLUTTER, true)
    fun setReduceClutter(context: Context, value: Boolean) = put(context, KEY_REDUCE_CLUTTER, value)

    fun resumeLastSession(context: Context): Boolean = prefs(context).getBoolean(KEY_RESUME_LAST_SESSION, false)
    fun setResumeLastSession(context: Context, value: Boolean) = put(context, KEY_RESUME_LAST_SESSION, value)

    /**
     * Whether the phone's GPS may be read for the speed display
     * ([dev.autobridge.speed.GpsSpeedSource]).
     *
     * Off by default, and the only thing that lets location be used for speed at all. Holding the
     * Android location permission is not enough: it may have been granted for a web page's
     * geolocation request, and a permission granted for one purpose is not consent for another.
     * The car settings row that turns this on shows what location will be used for first.
     */
    fun gpsSpeed(context: Context): Boolean = prefs(context).getBoolean(KEY_GPS_SPEED, false)
    fun setGpsSpeed(context: Context, value: Boolean) = put(context, KEY_GPS_SPEED, value)

    private fun put(context: Context, key: String, value: Boolean) {
        prefs(context).edit { putBoolean(key, value) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
