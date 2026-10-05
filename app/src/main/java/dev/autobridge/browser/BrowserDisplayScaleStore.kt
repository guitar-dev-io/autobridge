package dev.autobridge.browser

import android.content.Context
import android.webkit.WebView
import androidx.core.content.edit
import kotlin.math.roundToInt

/**
 * How large text and content are drawn in the page, as a percentage the user drags a slider to.
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

    /** The slider's range and step, in percent. 100 is the platform default. */
    const val MIN_PERCENT = 50
    const val MAX_PERCENT = 200
    const val STEP_PERCENT = 5

    const val DEFAULT_PERCENT = 100

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Clamps to the slider's range, then snaps to the nearest step so a value between two ticks never wedges a half-step zoom. */
    private fun snap(percent: Int): Int {
        val clamped = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)
        return MIN_PERCENT + ((clamped - MIN_PERCENT).toFloat() / STEP_PERCENT).roundToInt() * STEP_PERCENT
    }

    /** The stored percent, snapped so a stray write can never wedge an off-grid zoom. */
    fun percent(context: Context): Int = snap(prefs(context).getInt(KEY_SCALE, DEFAULT_PERCENT))

    fun setPercent(context: Context, percent: Int) {
        prefs(context).edit { putInt(KEY_SCALE, snap(percent)) }
    }

    /** Pushes the stored percent onto [webView]'s text zoom. Takes effect on the next layout/reload. */
    fun apply(context: Context, webView: WebView) {
        webView.settings.textZoom = percent(context)
    }
}
