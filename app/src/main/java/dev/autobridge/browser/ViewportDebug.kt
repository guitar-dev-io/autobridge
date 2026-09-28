package dev.autobridge.browser

import android.graphics.Rect
import android.util.Log
import dev.autobridge.BuildConfig

/**
 * One-line, greppable viewport traces used to find what actually moves the page.
 *
 * Every event prints the same fields, so two lines can be diffed directly: if the page appears to
 * shift, exactly one of `web=` (the WebView's measured size) or `vp=` (where it is composited)
 * will have changed, and the `ev=` tag names the trigger. Chrome-only transitions must leave both
 * unchanged — that is the property this logging exists to verify, and the one a "fixed" magic
 * number would hide.
 *
 * Off in release builds unless [verbose] is set, so a head unit is not spammed in normal use.
 */
object ViewportDebug {
    private const val TAG = "[AutoBridge/Viewport]"

    /** Named trigger points, matching the states a page can visibly move across. */
    object Event {
        const val SURFACE_AVAILABLE = "surface_available"
        const val SURFACE_RESIZED = "surface_resized"
        const val SURFACE_DESTROYED = "surface_destroyed"
        const val STABLE_AREA = "stable_area"
        const val STABLE_AREA_IGNORED = "stable_area_ignored"
        const val VISIBLE_AREA = "visible_area"
        const val FULLSCREEN = "fullscreen"
        const val DRAWER = "drawer"
        const val CHROME = "chrome"
        const val KEYBOARD = "keyboard"
        const val CONFIG_CHANGE = "config_change"
        const val TAB_SWITCH = "tab_switch"
        const val LAYOUT = "layout"
    }

    /** Enables tracing on release builds too; debug builds trace by default. */
    @Volatile
    var verbose: Boolean = false

    val enabled: Boolean get() = BuildConfig.DEBUG || verbose

    fun log(
        event: String,
        viewport: BrowserViewport?,
        surfaceWidth: Int,
        surfaceHeight: Int,
        surfaceDpi: Int,
        webViewDensity: Float,
        stableArea: Rect?,
        chromeVisible: Boolean,
        fullscreen: Boolean,
        drawerOpen: Boolean,
        extra: String = "",
    ) {
        if (!enabled) return
        val vp = viewport
        Log.i(
            TAG,
            buildString {
                append("ev=").append(event)
                append(" surface=").append(surfaceWidth).append('x').append(surfaceHeight)
                append(" dpi=").append(surfaceDpi)
                append(" surfaceDensity=").append(format(if (surfaceDpi > 0) surfaceDpi / 160f else 1f))
                append(" webDensity=").append(format(webViewDensity))
                if (vp != null) {
                    append(" vp=").append(vp.left).append(',').append(vp.top)
                        .append(' ').append(vp.width).append('x').append(vp.height)
                    append(" web=").append(vp.webWidth).append('x').append(vp.webHeight)
                    append(" scale=").append(format(vp.scale))
                    append(" cssWidth=").append(vp.contentWidthDp)
                } else {
                    append(" vp=none")
                }
                append(" stable=").append(stableArea?.let { "${it.left},${it.top},${it.right},${it.bottom}" } ?: "none")
                append(" chrome=").append(if (chromeVisible) "shown" else "hidden")
                append(" fullscreen=").append(fullscreen)
                append(" drawer=").append(drawerOpen)
                if (extra.isNotBlank()) append(' ').append(extra)
            }
        )
    }

    /** Trace for the phone activity, where insets rather than a car surface drive the geometry. */
    fun logWindow(
        event: String,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        fontScale: Float,
        systemBars: Rect?,
        imeBottom: Int,
        chromeVisible: Boolean,
        fullscreen: Boolean,
        extra: String = "",
    ) {
        if (!enabled) return
        Log.i(
            TAG,
            buildString {
                append("ev=").append(event)
                append(" window=").append(widthPx).append('x').append(heightPx)
                append(" density=").append(format(density))
                append(" dp=").append((widthPx / density).toInt()).append('x').append((heightPx / density).toInt())
                append(" fontScale=").append(format(fontScale))
                append(" clampedFontScale=").append(format(AutoUiSizes.clampFontScale(fontScale)))
                append(" systemBars=")
                    .append(systemBars?.let { "${it.left},${it.top},${it.right},${it.bottom}" } ?: "none")
                append(" ime=").append(imeBottom)
                append(" chrome=").append(if (chromeVisible) "shown" else "hidden")
                append(" fullscreen=").append(fullscreen)
                if (extra.isNotBlank()) append(' ').append(extra)
            }
        )
    }

    private fun format(value: Float): String = String.format("%.3f", value)
}
