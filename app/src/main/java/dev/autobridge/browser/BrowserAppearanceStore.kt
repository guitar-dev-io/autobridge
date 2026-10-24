package dev.autobridge.browser

import android.content.Context
import android.content.res.Configuration
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.core.content.edit
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import dev.autobridge.R
import dev.autobridge.design.ThemeHolder
import dev.autobridge.design.ThemeMode
import dev.autobridge.design.ThemePalette
import dev.autobridge.design.ThemeResolver
import dev.autobridge.settings.AppearanceStore

/**
 * The Appearance choice: Auto, Light or Dark, applied by [BrowserAppearanceStore].
 */
enum class BrowserAppearance(val label: String) {
    /**
     * Follow the rest of the app: on the phone, Settings > Appearance's light/dark choice; for the
     * car's pages, the system's night mode, as before.
     */
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
 *
 * Phone and car resolve AUTO differently. The phone browser is one more phone screen, so AUTO there
 * follows the app's Settings > Appearance palette ([phoneWantsDark], [phonePalette]) and the chrome
 * takes that palette's preset and accent. The car's pages ([wantsDark], [apply], used through
 * [BrowserDefaults.configure]) keep following the system night mode, exactly as they did.
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
        val palette = phonePalette(context)
        chromePalette = palette
        BrowserTheme.active = BrowserTheme.fromPalette(palette)
    }

    /** The palette [syncChrome] last built the chrome from; null before the first build. */
    @Volatile
    private var chromePalette: ThemePalette? = null

    /**
     * The app palette the phone chrome draws with. AUTO is the app's own palette as it stands
     * ([ThemeHolder.palette]); Light or Dark keep the user's preset and accent but force that side,
     * so picking "Light" here on a dark app still gets the app's accent rather than a stock blue.
     */
    fun phonePalette(context: Context): ThemePalette {
        val app = ThemeHolder.palette
        return when (mode(context)) {
            BrowserAppearance.AUTO -> app
            BrowserAppearance.LIGHT ->
                if (app.isLight) app else ThemeResolver.resolve(ThemeHolder.spec.copy(mode = ThemeMode.LIGHT))
            BrowserAppearance.DARK ->
                if (!app.isLight) app else ThemeResolver.resolve(ThemeHolder.spec.copy(mode = ThemeMode.DARK))
        }
    }

    /** Whether the phone browser is dark: [phonePalette]'s side. */
    fun phoneWantsDark(context: Context): Boolean = !phonePalette(context).isLight

    /**
     * True when the palette the chrome was built from is no longer the one it would be built from
     * now: the app's Appearance changed while the browser sat in the back stack, or the system or
     * the clock moved a System/Auto theme across the line. The caller refreshes [ThemeHolder] first.
     */
    fun chromeIsStale(context: Context): Boolean =
        chromePalette.let { it != null && it != phonePalette(context) }

    /**
     * The activity theme to use for this preference.
     *
     * WebView decides what to report for `prefers-color-scheme` from the `android:isLightTheme`
     * attribute of the context it was *constructed* with, so a light preference on a dark-themed
     * activity leaves every site that has its own dark theme dark. These two themes differ in that
     * attribute, and in the system bar colours that frame the window.
     */
    fun activityTheme(context: Context): Int =
        if (phoneWantsDark(context)) R.style.BrowserThemeDark else R.style.BrowserThemeLight

    /**
     * Re-bases [base] into the night mode this preference resolves to.
     *
     * Belt and braces next to [activityTheme]: the theme attribute is what WebView reads, while the
     * configuration is what resource qualifiers and anything else asking "is it night" read. An
     * activity whose theme and configuration disagreed would be a trap for the next change here.
     */
    fun rebase(base: Context): Context {
        val configuration = base.resources.configuration
        val night = if (phoneWantsDark(base)) {
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

    /**
     * Whether a car page should be darkened right now, resolving [BrowserAppearance.AUTO] against the
     * system. The phone browser uses [phoneWantsDark] instead.
     */
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
     * True when a configuration change moved the system across the night-mode line while the phone browser is
     * following it (AUTO on an app theme set to System), so the window has to be rebuilt to change
     * scheme.
     *
     * The browser handles `uiMode` itself (it is in its `configChanges`, so the keyboard and the
     * car panel do not tear the page down), which means nothing rebuilds it on a system theme
     * change unless this says so. The app palette is re-resolved from the *application* context,
     * which carries the system's night mode; this activity's own context reads back the night mode
     * [rebase] pinned there, and resolving against that would feed the browser's choice back into
     * the whole app's palette.
     */
    fun needsRestart(context: Context): Boolean {
        AppearanceStore.refreshPalette(context.applicationContext)
        return chromeIsStale(context)
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
    fun apply(context: Context, webView: WebView) = applyDarkening(webView, wantsDark(context))

    /** [apply] for the phone browser's WebViews, resolved the phone's way ([phoneWantsDark]). */
    fun applyPhone(context: Context, webView: WebView) = applyDarkening(webView, phoneWantsDark(context))

    private fun applyDarkening(webView: WebView, dark: Boolean) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) return
        runCatching { WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, dark) }
    }
}
