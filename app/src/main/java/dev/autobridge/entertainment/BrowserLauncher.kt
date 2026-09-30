package dev.autobridge.entertainment

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.display.MirrorDiagnostics

/** Opens a browser only when the centralized parked/personal/lab policy allows it. */
object BrowserLauncher {
    private const val TAG = "AutoBridgeBrowser"

    fun canOpen(): Boolean = FeaturePolicy.app.isAvailable(Feature.BROWSER)

    /**
     * Opens a provider sign-in page as a Custom Tab instead of handing off to a full external
     * browser app. A Custom Tab renders inside Chrome's own process and shares its cookie jar, so a
     * device already signed in to Chrome skips the credential prompt entirely. Falls back to
     * [openUrl] when no Custom Tabs-capable browser is installed.
     *
     * Sharing Chrome's cookie jar cuts only one way, and that limit is why this path is a last
     * resort rather than the default. The session a Custom Tab establishes is written to Chrome's
     * cookie store, which this app's WebView cannot read — so signing in here does not leave the
     * page in [dev.autobridge.browser.CarWebRenderer] signed in. For the hosts still routed through
     * here (Apple and Microsoft, per `BrowserDefaults.externalSignInHosts`) that makes it a dead
     * end: sign-in succeeds in Chrome and the car surface is unchanged. Google is deliberately not
     * on that list — it signs in inside this app's own WebView, where the resulting cookies land in
     * the process-wide [android.webkit.CookieManager] that both presentations share.
     *
     * Deliberately does NOT set `FLAG_ACTIVITY_NEW_TASK`: that flag is what makes Custom Tabs
     * launch into a separate task instead of stacking on top of the caller, which looks and
     * behaves exactly like a full app switch (the thing this is meant to avoid). Requires
     * [context] to be an Activity context, which is what every current caller passes.
     */
    fun openSignIn(context: Context, input: String): Boolean {
        if (!allowBrowserLaunch()) return false
        val url = ContentAddress.https(input) ?: return false
        val uri = Uri.parse(url)

        val customTabIntent = CustomTabsIntent.Builder().build().intent.apply {
            data = uri
        }
        if (customTabIntent.resolveActivity(context.packageManager) != null) {
            return runCatching {
                context.startActivity(customTabIntent)
                MirrorDiagnostics.record("external_signin_custom_tab_opened")
                Log.i(TAG, "Started Custom Tab sign-in for $uri")
                true
            }.onFailure { error ->
                Log.w(TAG, "Unable to start Custom Tab sign-in", error)
            }.getOrDefault(false)
        }
        return openUrl(context, input)
    }

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
