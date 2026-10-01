package dev.autobridge.browser

import android.content.Context
import androidx.core.content.edit

/**
 * Whether the browser forces Widevine L3 (software) DRM instead of letting the page negotiate the
 * device's highest level.
 *
 * On many head units and some phones the hardware-backed L1 path renders protected video as a black
 * screen (the secure surface the host composites is not captured by the browser's own rendering) or
 * fails playback outright, while the same content plays when the site is served the lower,
 * software-decoded L3 level. Enforcing L3 trades maximum streaming resolution — some services cap
 * L3 at 480p/720p — for a picture that actually appears, which is the right trade when the
 * alternative is nothing on screen.
 *
 * This store only records the preference; [BrowserDefaults] reads it when configuring a WebView and
 * sets the system property Widevine consults to pick a level. Off by default: it costs resolution,
 * so it is opt-in for someone who hit the black-screen symptom.
 */
object BrowserDrmStore {
    private const val PREFS_NAME = "autobridge_browser"
    private const val KEY_ENFORCE_L3 = "drm_enforce_widevine_l3"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun enforceL3(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENFORCE_L3, false)

    fun setEnforceL3(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENFORCE_L3, enabled) }
    }
}
