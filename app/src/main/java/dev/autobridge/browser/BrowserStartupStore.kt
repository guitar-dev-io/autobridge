package dev.autobridge.browser

import android.content.Context
import androidx.core.content.edit
import dev.autobridge.entertainment.ContentAddress

/** What the browser opens when it is launched cold (no URL handed in by an intent or saved state). */
enum class BrowserLaunchBehavior(val label: String) {
    /** Open the configured home page every time. */
    HOME_PAGE("Home page"),

    /** Reopen the last page that was shown. */
    RESUME_LAST("Resume last page"),
}

/**
 * Start-up and start-page preferences, in the shared "autobridge_browser" preference file.
 *
 * Three things live here because they are the three decisions a user makes about "what do I see
 * when I open the browser": which page is Home, whether a cold launch lands on Home or on wherever
 * they left off, and whether the start page is drawn over the app's gradient. The raw material for
 * "resume last page" already exists ([BrowserDefaults.lastUrl]); what was missing was a switch to
 * choose it over Home, which is what [launchBehavior] now is.
 */
object BrowserStartupStore {
    private const val PREFS_NAME = "autobridge_browser"
    private const val KEY_HOME = "startup_home_url"
    private const val KEY_BEHAVIOR = "startup_behavior"
    private const val KEY_GRADIENT = "startup_gradient_background"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The configured home page, or [BrowserDefaults.HOME] when none has been set. */
    fun homePage(context: Context): String =
        prefs(context).getString(KEY_HOME, BrowserDefaults.HOME)
            ?.let(ContentAddress::https) ?: BrowserDefaults.HOME

    /**
     * Stores [url] as the home page after normalising it to an HTTPS URL. Returns false (and stores
     * nothing) when it is not a resolvable address, so a typo cannot leave Home pointing nowhere.
     */
    fun setHomePage(context: Context, url: String): Boolean {
        val valid = ContentAddress.https(url.trim()) ?: return false
        prefs(context).edit { putString(KEY_HOME, valid) }
        return true
    }

    fun launchBehavior(context: Context): BrowserLaunchBehavior =
        runCatching {
            BrowserLaunchBehavior.valueOf(
                prefs(context).getString(KEY_BEHAVIOR, BrowserLaunchBehavior.RESUME_LAST.name).orEmpty()
            )
        }.getOrDefault(BrowserLaunchBehavior.RESUME_LAST)

    fun setLaunchBehavior(context: Context, behavior: BrowserLaunchBehavior) {
        prefs(context).edit { putString(KEY_BEHAVIOR, behavior.name) }
    }

    /**
     * The URL a cold launch should open, resolving [launchBehavior] against the stored last page.
     * [HOME_PAGE][BrowserLaunchBehavior.HOME_PAGE] gives the configured home; [RESUME_LAST] gives
     * the last page, falling back to Home when nothing was remembered.
     */
    fun coldStartUrl(context: Context): String = when (launchBehavior(context)) {
        BrowserLaunchBehavior.HOME_PAGE -> homePage(context)
        BrowserLaunchBehavior.RESUME_LAST -> BrowserDefaults.lastUrl(context)
    }

    /**
     * Whether the start page is drawn over the app's default gradient rather than a flat surface.
     * On by default — the gradient is the branded start-page look; turning it off gives a plain
     * background for anyone who finds it busy.
     */
    fun gradientBackground(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GRADIENT, true)

    fun setGradientBackground(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_GRADIENT, enabled) }
    }
}
