package dev.autobridge.browser

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.webkit.GeolocationPermissions
import dev.autobridge.R

/**
 * Web geolocation (`navigator.geolocation`) for the app's WebViews.
 *
 * A WebView never hands a page its location on its own: [android.webkit.WebChromeClient]'s default
 * `onGeolocationPermissionsShowPrompt` does nothing, so the request is never answered and map sites
 * report "location unavailable". Two gates have to open for a page to get a fix:
 *
 * 1. The per-origin WebView grant — answered through [GeolocationPermissions.Callback]. A grant made
 *    with `retain = true` is stored in the process-wide WebView geolocation database, so it applies to
 *    every WebView in the app (phone browser, car renderer, player) until "รีเซ็ตสิทธิ์" clears it.
 * 2. The app's own Android runtime location permission — without it Chromium has no provider to read
 *    even after the page is allowed.
 *
 * Only HTTPS origins are ever eligible; browsers expose geolocation to secure contexts only, and the
 * app's browsers load nothing else anyway.
 */
object BrowserGeolocation {
    private const val TAG = "[AutoBridge/Geo]"

    /** Request code used by [Prompter] for the runtime location permission. */
    const val REQUEST_LOCATION = 7301

    private val LOCATION_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    fun hasAppPermission(context: Context): Boolean = LOCATION_PERMISSIONS.any {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    fun isEligibleOrigin(origin: String): Boolean =
        runCatching { Uri.parse(origin).scheme.equals("https", ignoreCase = true) }.getOrDefault(false)

    private fun displayHost(origin: String): String =
        runCatching { Uri.parse(origin).host }.getOrNull() ?: origin

    /**
     * Car-surface handler ([CarWebRenderer]). The car WebView is drawn to a Surface with no Activity
     * behind it, so it cannot show a per-site dialog or the system permission sheet. Origins the user
     * already allowed on the phone never reach this (the retained grant answers them); for the rest an
     * HTTPS page is allowed for this session only when the app already holds location permission.
     * Otherwise it is denied and [onMissingPermission] tells the caller to point the user at the phone.
     */
    fun answerForCar(
        context: Context,
        origin: String,
        callback: GeolocationPermissions.Callback,
        onMissingPermission: () -> Unit,
    ) {
        val eligible = isEligibleOrigin(origin)
        val permitted = hasAppPermission(context)
        val grant = eligible && permitted
        callback.invoke(origin, grant, false)
        Log.i(TAG, "car prompt origin=$origin eligible=$eligible appPermission=$permitted granted=$grant")
        if (eligible && !permitted) onMissingPermission()
    }

    /**
     * Phone-side handler for an [Activity] that owns a WebView: asks the user per site, then asks
     * Android for the runtime permission if the app does not hold it yet. The host Activity forwards
     * `onRequestPermissionsResult`, `onGeolocationPermissionsHidePrompt` and `onDestroy` here.
     */
    class Prompter(private val activity: Activity) {
        private var dialog: AlertDialog? = null
        private var pendingOrigin: String? = null
        private var pendingCallback: GeolocationPermissions.Callback? = null

        fun show(origin: String, callback: GeolocationPermissions.Callback) {
            // A new prompt supersedes any unanswered one; the old page is told "no".
            cancelPending()
            if (!isEligibleOrigin(origin) || activity.isFinishing || activity.isDestroyed) {
                callback.invoke(origin, false, false)
                return
            }
            pendingOrigin = origin
            pendingCallback = callback
            dialog = AlertDialog.Builder(activity)
                .setTitle(R.string.geo_title)
                .setMessage(activity.getString(R.string.geo_message, displayHost(origin)))
                .setPositiveButton(R.string.geo_allow) { _, _ -> onUserAllowed() }
                .setNegativeButton(R.string.geo_deny) { _, _ -> finish(granted = false) }
                .setOnCancelListener { finish(granted = false) }
                .show()
        }

        /** WebChromeClient.onGeolocationPermissionsHidePrompt: the page withdrew the request. */
        fun hide() {
            dialog?.setOnCancelListener(null)
            dialog?.dismiss()
            dialog = null
            pendingOrigin = null
            pendingCallback = null
        }

        /** Returns true when [requestCode] was this prompter's runtime-permission request. */
        fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray): Boolean {
            if (requestCode != REQUEST_LOCATION) return false
            val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            if (!granted) toastDenied()
            finish(granted = granted)
            return true
        }

        fun release() = cancelPending()

        private fun onUserAllowed() {
            dialog = null
            if (hasAppPermission(activity)) {
                finish(granted = true)
            } else {
                // The answer arrives in onRequestPermissionsResult; the callback stays pending.
                activity.requestPermissions(LOCATION_PERMISSIONS, REQUEST_LOCATION)
            }
        }

        private fun finish(granted: Boolean) {
            dialog = null
            val origin = pendingOrigin ?: return
            val callback = pendingCallback ?: return
            pendingOrigin = null
            pendingCallback = null
            // Retained only when allowed, so the car renderer and later visits reuse the grant
            // without asking; a refusal is not remembered, so the site can ask again next visit.
            callback.invoke(origin, granted, granted)
            Log.i(TAG, "phone prompt origin=$origin granted=$granted")
        }

        private fun cancelPending() {
            dialog?.setOnCancelListener(null)
            dialog?.dismiss()
            finish(granted = false)
        }

        private fun toastDenied() {
            android.widget.Toast.makeText(
                activity,
                activity.getString(R.string.geo_permission_denied),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
}
