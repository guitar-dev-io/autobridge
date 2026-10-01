package dev.autobridge.browser

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.util.Log

/**
 * A display that exists only to carry a density for the car WebView's [Context].
 *
 * Chromium takes a WebView's device scale factor from the display of the Context the WebView was
 * *constructed* with (`WindowAndroid` → `DisplayAndroid.getNonMultiDisplay(context)`), not from the
 * window it is later attached to. For the application context that is the phone's own display, so
 * the car WebView always scaled by the phone's ~3.0 and an 800px car view laid pages out at
 * 267 CSS px (`pageScale=3.000`). Neither `setInitialScale` (it cannot widen a layout) nor the
 * hosting window's density changes that — both were tried and both left the trace at 3.000.
 *
 * Building the WebView on `createDisplayContext(this display)` hands Chromium a density we choose:
 * [BrowserViewport.pageDensityDpi], at which the car view is exactly the wanted CSS width. The
 * display has no surface and hosts no window, so it can be resized in place when that density
 * moves (desktop toggle, surface size change) and Chromium follows through its display listener.
 */
class PageDensityDisplay private constructor(
    private val display: VirtualDisplay,
    /** The Context to construct the WebView with. */
    val context: Context,
) {
    var densityDpi: Int = display.display.let { d ->
        android.util.DisplayMetrics().also { d.getRealMetrics(it) }.densityDpi
    }
        private set

    /** Moves the density (and size) the WebView scales by. A no-op when nothing changed. */
    fun update(width: Int, height: Int, densityDpi: Int) {
        val dpi = densityDpi.coerceAtLeast(MIN_DENSITY_DPI)
        if (width <= 0 || height <= 0) return
        if (dpi == this.densityDpi && width == lastWidth && height == lastHeight) return
        runCatching { display.resize(width, height, dpi) }
            .onSuccess {
                this.densityDpi = dpi
                lastWidth = width
                lastHeight = height
                Log.i(TAG, "page density display ${width}x$height @${dpi}dpi")
            }
            .onFailure { Log.w(TAG, "page density display resize failed", it) }
    }

    private var lastWidth = 0
    private var lastHeight = 0

    fun release() {
        runCatching { display.release() }
    }

    companion object {
        private const val TAG = "AutoBridgeCarWeb"
        private const val MIN_DENSITY_DPI = 60

        /**
         * Null when the platform refuses (or predates `Context.getDisplay`, API 30, which is how
         * Chromium reads a context's display); the caller then keeps the application context.
         */
        fun create(context: Context, width: Int, height: Int, densityDpi: Int): PageDensityDisplay? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            if (width <= 0 || height <= 0) return null
            val dpi = densityDpi.coerceAtLeast(MIN_DENSITY_DPI)
            return runCatching {
                val displays = context.getSystemService(DisplayManager::class.java)
                    ?: error("DisplayManager unavailable")
                val virtual = displays.createVirtualDisplay(
                    "AutoBridgePageDensity", width, height, dpi, null,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                ) ?: error("createVirtualDisplay returned null")
                PageDensityDisplay(virtual, context.createDisplayContext(virtual.display)).also {
                    it.lastWidth = width
                    it.lastHeight = height
                    Log.i(TAG, "page density display created ${width}x$height @${dpi}dpi id=${virtual.display.displayId}")
                }
            }.onFailure { Log.w(TAG, "page density display unavailable; using the phone density", it) }
                .getOrNull()
        }
    }
}
