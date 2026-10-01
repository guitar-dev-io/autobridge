package dev.autobridge.browser

import android.content.Context
import android.content.res.Configuration
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.core.content.edit
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * Which colour scheme the browser presents a page in.
 *
 * This is a page-rendering preference, not an app-chrome one: the sheet and toolbar stay on the
 * fixed dark [BrowserTheme] (a car display is treated as dark throughout, and recolouring the
 * Canvas chrome the car surface shares would ripple far past the phone). What the user actually
 * asks for with "Light / Dark / Auto" is how *the web page* looks, so this drives WebView's
 * algorithmic darkening — the standard, scoped lever that makes a site honour a dark preference
 * when it has no dark theme of its own, and leaves it alone when it does.
 */
enum class BrowserAppearance(val label: String) {
    /** Follow the system's night mode. */
    AUTO("Auto"),

    /** Always render the page light: darkening off whatever the system says. */
    LIGHT("Light"),

    /** Always render the page dark: darkening on whatever the system says. */
    DARK("Dark"),
}

/**
 * Persistent [BrowserAppearance] selection, in the shared "autobridge_browser" preference file so
 * the whole browser reads one file.
 */
object BrowserAppearanceStore {
    private const val PREFS_NAME = "autobridge_browser"
    private const val KEY_MODE = "appearance_mode"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun mode(context: Context): BrowserAppearance =
        runCatching {
            BrowserAppearance.valueOf(
                prefs(context).getString(KEY_MODE, BrowserAppearance.AUTO.name).orEmpty()
            )
        }.getOrDefault(BrowserAppearance.AUTO)

    fun select(context: Context, mode: BrowserAppearance) {
        prefs(context).edit { putString(KEY_MODE, mode.name) }
    }

    /** Whether the page should be darkened right now, resolving [BrowserAppearance.AUTO] against the system. */
    fun wantsDark(context: Context): Boolean = when (mode(context)) {
        BrowserAppearance.AUTO -> isSystemNight(context)
        BrowserAppearance.LIGHT -> false
        BrowserAppearance.DARK -> true
    }

    private fun isSystemNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * Pushes the resolved preference onto [webView].
     *
     * Uses algorithmic darkening ([WebSettingsCompat.setAlgorithmicDarkeningAllowed]) where the
     * installed WebView supports it: that API darkens a page that has no `prefers-color-scheme: dark`
     * styling of its own while leaving a page that does to its own theme, which is exactly the
     * "make it dark if it isn't already" meaning of a Dark setting. On WebViews too old for it this
     * is a no-op and the page renders as it always did.
     */
    fun apply(context: Context, webView: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) return
        runCatching {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, wantsDark(context))
        }
    }
}
