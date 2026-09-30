package dev.autobridge.browser

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.Display

/**
 * Density for a browser window shown on the car display rather than the phone's.
 *
 * An activity on the Android Auto display already receives that display's own configuration, so
 * unlike the template path ([CarWebRenderer]) nothing has to be laid out large and scaled down —
 * the page is rasterised once, at the size it is shown. What the display's own density does *not*
 * decide is whether the result is readable at arm's length: head units report anything from ~160
 * to ~280 dpi for panels of similar physical size, so the same page lands anywhere between a
 * cramped phone layout and a desktop one.
 *
 * This pins the CSS width the page sees to the band [BrowserViewport.MIN_CONTENT_WIDTH_DP] ..
 * [BrowserViewport.MAX_CONTENT_WIDTH_DP] — the same band the car surface already enforces — by
 * adjusting the density instead of the layout size. Both routes onto the head unit therefore lay
 * the page out identically, and a site's responsive breakpoints cannot resolve differently
 * depending on which one the user happened to open.
 */
object CarDisplayScaling {

    /** Below this the UI stops being legible at all; Android's own floor for a display. */
    private const val MIN_DENSITY_DPI = 120

    /**
     * The density that puts a [widthPx]-wide display inside the content-width band, or
     * [densityDpi] unchanged when it already is. Pure arithmetic so it can be checked on the JVM.
     */
    fun densityDpiFor(widthPx: Int, densityDpi: Int): Int {
        if (widthPx <= 0 || densityDpi <= 0) return densityDpi
        val contentWidthDp = widthPx * 160 / densityDpi
        val target = when {
            contentWidthDp < BrowserViewport.MIN_CONTENT_WIDTH_DP -> BrowserViewport.MIN_CONTENT_WIDTH_DP
            contentWidthDp > BrowserViewport.MAX_CONTENT_WIDTH_DP -> BrowserViewport.MAX_CONTENT_WIDTH_DP
            else -> return densityDpi
        }
        return (widthPx * 160 / target).coerceAtLeast(MIN_DENSITY_DPI)
    }

    /**
     * [base] re-based on the density from [densityDpiFor], or [base] itself when this is the phone
     * display or the density already suits the panel.
     *
     * Applied through `attachBaseContext` so the whole activity — toolbar, dialogs and the WebView's
     * own CSS-pixel mapping — agrees on one density. Overriding only the WebView would leave the
     * chrome sized for the other one.
     */
    fun rebase(base: Context): Context {
        // Context.getDisplay() is API 30; below that a secondary-display activity is not something
        // this app can be launched into anyway, so the phone density is the right answer.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return base
        val display = runCatching { base.display }.getOrNull() ?: return base
        if (display.displayId == Display.DEFAULT_DISPLAY) return base
        val configuration = base.resources.configuration
        val widthPx = base.resources.displayMetrics.widthPixels
        val next = densityDpiFor(widthPx, configuration.densityDpi)
        if (next == configuration.densityDpi) return base
        return base.createConfigurationContext(Configuration(configuration).apply { densityDpi = next })
    }
}
