package dev.autobridge.browser

import android.content.Context
import android.webkit.WebView
import androidx.core.content.edit

/**
 * How large text and content are drawn in the page, as a percentage the user picks from a fixed
 * ladder.
 *
 * This is distinct from [CarDisplayScaling], which decides the CSS *width* a car panel reports so a
 * site's responsive layout resolves the same on every head unit. That is about which layout a site
 * serves; this is about how big that layout is drawn once it is chosen, and it applies on the phone
 * too. It is carried through [android.webkit.WebSettings.textZoom], which scales text (and, for
 * pages that size with it, their layout) without changing the viewport width, so a site's
 * breakpoints do not flip between mobile and desktop just because the user wanted larger text.
 */
object BrowserDisplayScaleStore {
    private const val PREFS_NAME = "autobridge_browser"
    private const val KEY_SCALE = "display_scale_percent"

    /** The ladder the settings sheet offers, in percent. 100 is the platform default. */
    val STEPS = listOf(85, 100, 115, 130, 150)

    const val DEFAULT_PERCENT = 100

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The stored percent, snapped to the nearest [STEPS] value so a stray write can never wedge an off-ladder zoom. */
    fun percent(context: Context): Int {
        val stored = prefs(context).getInt(KEY_SCALE, DEFAULT_PERCENT)
        return STEPS.minByOrNull { kotlin.math.abs(it - stored) } ?: DEFAULT_PERCENT
    }

    fun setPercent(context: Context, percent: Int) {
        val snapped = STEPS.minByOrNull { kotlin.math.abs(it - percent) } ?: DEFAULT_PERCENT
        prefs(context).edit { putInt(KEY_SCALE, snapped) }
    }

    /** Pushes the stored percent onto [webView]'s text zoom. Takes effect on the next layout/reload. */
    fun apply(context: Context, webView: WebView) {
        webView.settings.textZoom = percent(context)
    }
}
