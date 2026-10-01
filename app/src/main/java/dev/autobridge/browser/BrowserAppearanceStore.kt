package dev.autobridge.browser

import android.content.Context
import android.content.res.Configuration
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.core.content.edit
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import dev.autobridge.R

/**
 * The Appearance choice: Auto, Light or Dark, applied by [BrowserAppearanceStore].
 */
enum class BrowserAppearance(val label: String) {
    /** Follow the system's night mode. */
    AUTO("Auto"),

    /** Light chrome and a light page, whatever the system says. */
    LIGHT("Light"),

    /** Dark chrome and a dark page, whatever the system says. */
    DARK("Dark"),
}

/**
 * Persistent [BrowserAppearance] selection — in the shared "autobridge_browser" preference
 * file, so the whole browser reads one file — and everything that makes the choice real for
 * both the page and the phone's own chrome.
 *
 * It used to be a page-only preference, which is why picking "Light" looked like it did nothing —
 * the toolbar, sheets and tab switcher stayed dark whatever was chosen, and a page with a dark
 * theme of its own stayed dark too because WebView takes the scheme it reports to a page from the
 * hosting activity's theme, which was fixed dark. Three things have to agree for the choice to be
 * real, and this store owns all three:
 *
 *  - [syncChrome] picks the [BrowserColors] the phone chrome draws with;
 *  - [rebase] and [activityTheme] put the hosting activity (and so every WebView built from it)
 *    into the matching night mode and `isLightTheme`, which is what decides the page's
 *    `prefers-color-scheme`;
 *  - [apply] allows algorithmic darkening, which darkens pages that have no dark theme at all.
 *
 * The car surface is deliberately left out: it always draws [BrowserTheme.dark], because a head
 * unit runs its own UI dark and a light chrome inside that frame reads as a hole in the dashboard.
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
        syncChrome(context)
    }

    /**
     * Points [BrowserTheme] at the scheme this preference resolves to, for the phone chrome.
     *
     * Call before building any browser view. Views already built keep the colours they were given,
     * which is why a change restarts the browser activity rather than re-tinting it: the chrome is
     * a few hundred Views, drawables and Paints, and half of them would be missed.
     */
    fun syncChrome(context: Context) {
        BrowserTheme.active = if (wantsDark(context)) BrowserTheme.dark else BrowserTheme.light
    }

    /**
     * The activity theme to use for this preference.
     *
     * WebView decides what to report for `prefers-color-scheme` from the `android:isLightTheme`
     * attribute of the context it was *constructed* with, so a light preference on a dark-themed
     * activity leaves every site that has its own dark theme dark. These two themes differ in that
     * attribute, and in the system bar colours that frame the window.
     */
    fun activityTheme(context: Context): Int =
        if (wantsDark(context)) R.style.BrowserThemeDark else R.style.BrowserThemeLight

    /**
     * Re-bases [base] into the night mode this preference resolves to.
     *
     * Belt and braces next to [activityTheme]: the theme attribute is what WebView reads, while the
     * configuration is what resource qualifiers and anything else asking "is it night" read. An
     * activity whose theme and configuration disagreed would be a trap for the next change here.
     */
    fun rebase(base: Context): Context {
        val configuration = base.resources.configuration
        val night = if (wantsDark(base)) {
            Configuration.UI_MODE_NIGHT_YES
        } else {
            Configuration.UI_MODE_NIGHT_NO
        }
        if ((configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == night) return base
        return base.createConfigurationContext(
            Configuration(configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            }
        )
    }

    /** Whether the page should be darkened right now, resolving [BrowserAppearance.AUTO] against the system. */
    fun wantsDark(context: Context): Boolean = when (mode(context)) {
        BrowserAppearance.AUTO -> isSystemNight(context)
        BrowserAppearance.LIGHT -> false
        BrowserAppearance.DARK -> true
    }

    /**
     * Asked of the *application* context on purpose. The browser activity re-bases its own
     * configuration to the resolved preference ([rebase]), so asking the activity what the system
     * is doing would only read back the answer we pinned there — and AUTO would freeze at whatever
     * the system happened to be when the window opened.
     */
    private fun isSystemNight(context: Context): Boolean =
        (context.applicationContext.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /**
     * True when [newConfig] moves the system across the night-mode line while the browser is
     * following it, so the window has to be rebuilt to change scheme.
     *
     * The browser handles `uiMode` itself (it is in its `configChanges`, so the keyboard and the
     * car panel do not tear the page down), which means nothing rebuilds it on a system theme
     * change unless this says so.
     */
    fun needsRestart(context: Context, newConfig: Configuration): Boolean {
        if (mode(context) != BrowserAppearance.AUTO) return false
        val night = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return night != (BrowserTheme.active === BrowserTheme.dark)
    }

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
