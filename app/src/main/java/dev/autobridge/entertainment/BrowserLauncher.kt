package dev.autobridge.entertainment

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.display.MirrorDiagnostics

/** Opens a browser only when the centralized parked/personal/lab policy allows it. */
object BrowserLauncher {
    private const val TAG = "AutoBridgeBrowser"

    fun canOpen(): Boolean = FeaturePolicy.app.isAvailable(Feature.BROWSER)

    /** Opens the device browser app without forcing a particular website. */
    fun launch(context: Context): Boolean {
        if (!allowBrowserLaunch()) return false

        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_BROWSER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return start(context, intent, "external_browser_launched")
    }

    /** Opens a validated HTTPS address in the user's browser. */
    fun openUrl(context: Context, input: String): Boolean {
        if (!allowBrowserLaunch()) return false
        val url = ContentAddress.https(input) ?: return false

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return start(context, intent, "external_browser_url_opened")
    }

    private fun allowBrowserLaunch(): Boolean =
        FeaturePolicy.app.isAvailable(Feature.BROWSER)

    private fun start(context: Context, intent: Intent, event: String): Boolean {
        if (intent.resolveActivity(context.packageManager) == null) {
            Log.w(TAG, "No browser activity resolved for ${intent.action}")
            return false
        }
        return runCatching {
            context.startActivity(intent)
            MirrorDiagnostics.record(event)
            Log.i(TAG, "Started browser action=${intent.action}")
            true
        }.onFailure { error ->
            Log.w(TAG, "Unable to start browser action=${intent.action}", error)
        }.getOrDefault(false)
    }
}
