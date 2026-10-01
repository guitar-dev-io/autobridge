package dev.autobridge.browser

import android.content.Context
import android.util.Log
import android.view.Surface
import android.view.View

/**
 * How the car browser turns its WebView into pixels on the Android Auto surface.
 *
 *  - [HARDWARE]: the WebView lives in a real, hardware-accelerated window on a [android.hardware.display.VirtualDisplay]
 *    whose sink *is* the Android Auto [Surface] (see [CarHardwareWebWindow]). SurfaceFlinger composites the
 *    page, Chromium's GPU compositor output and its video layers straight into the car surface, so
 *    `<video>` frames are visible. This is the same plumbing Fermata Auto uses for its Car App Library
 *    route (`MirrorDisplay`: a VirtualDisplay created on `SurfaceContainer.getSurface()` with
 *    `setSurface(null)` / `setSurface(new)` across surface churn) — here with own content instead of a mirror.
 *  - [LEGACY_CANVAS]: the old `Surface.lockCanvas()` + `WebView.draw(Canvas)` path. It cannot capture
 *    Chromium's separately composited video layer (video is black), and is kept only as a rollback.
 */
enum class CarBrowserRenderMode {
    HARDWARE,
    LEGACY_CANVAS;

    companion object {
        private const val PREFS = "car_browser_render"
        private const val KEY_MODE = "mode"

        /** The persisted mode. Defaults to [HARDWARE]; an unknown value also falls back to it. */
        fun current(context: Context): CarBrowserRenderMode {
            val stored = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MODE, null)
            return entries.firstOrNull { it.name == stored } ?: HARDWARE
        }

        /** Rollback switch. Takes effect the next time the car renderer is created. */
        fun select(context: Context, mode: CarBrowserRenderMode) {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_MODE, mode.name).apply()
        }
    }
}

/**
 * Dedicated logger for the car video path: `adb logcat -s AutoBridgeVideo`.
 * Logs state transitions only (surface, window, fullscreen, URL), never per frame.
 */
object AutoBridgeVideoLog {
    const val TAG = "AutoBridgeVideo"

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun w(message: String, error: Throwable? = null) {
        if (error != null) Log.w(TAG, message, error) else Log.w(TAG, message)
    }

    fun surface(event: String, surface: Surface?, width: Int, height: Int, dpi: Int) {
        Log.i(TAG, "car-surface $event valid=${surface?.isValid} size=${width}x$height dpi=$dpi")
    }

    /** One line with every display id that matters for "is this view really on the car display". */
    fun displays(stage: String, mode: CarBrowserRenderMode, targetDisplayId: Int?, window: View?, webView: View?, url: String?) {
        Log.i(
            TAG,
            "stage=$stage mode=$mode targetDisplayId=$targetDisplayId " +
                "windowDisplayId=${window?.display?.displayId} " +
                "webViewDisplayId=${webView?.display?.displayId} " +
                "webViewAttached=${webView?.isAttachedToWindow} hwAccel=${webView?.isHardwareAccelerated} " +
                "url=$url"
        )
    }
}
