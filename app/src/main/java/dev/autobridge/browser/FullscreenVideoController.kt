package dev.autobridge.browser

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.widget.FrameLayout

/**
 * Hosts a WebView's HTML5 fullscreen custom view (e.g. a `<video>` element gone fullscreen,
 * including EME/Widevine playback) inside [contentRoot]. This is the container
 * `WebChromeClient.onShowCustomView`/`onHideCustomView` need — without one, a page's own
 * fullscreen request has nowhere to render and silently does nothing.
 *
 * [window] is the window that owns [contentRoot]: an Activity's on the phone, the car
 * [android.app.Presentation]'s on Android Auto ([CarHardwareWebWindow]). It is only used to keep the
 * screen on while a video is fullscreen, so null is accepted.
 */
class FullscreenVideoController(
    private val context: Context,
    private val contentRoot: FrameLayout,
    private val window: Window?,
) {
    constructor(activity: Activity, contentRoot: FrameLayout) : this(activity, contentRoot, activity.window)

    private companion object {
        const val TAG = "[AutoBridge/Fullscreen]"
    }

    private var customViewContainer: FrameLayout? = null
    private var callback: WebChromeClient.CustomViewCallback? = null
    private var onFullscreenChanged: ((Boolean) -> Unit)? = null

    val isShowing: Boolean get() = customViewContainer != null

    /** The view currently shown fullscreen, so a caller that injects input can target it. */
    val container: View? get() = customViewContainer

    fun show(view: View, callback: WebChromeClient.CustomViewCallback, onFullscreenChanged: (Boolean) -> Unit) {
        if (customViewContainer != null) {
            // Chromium's contract: a second onShowCustomView before the first is hidden must be
            // rejected by immediately invoking the new callback's onCustomViewHidden().
            callback.onCustomViewHidden()
            return
        }
        Log.i(TAG, "show custom view (${context.javaClass.simpleName})")
        this.callback = callback
        this.onFullscreenChanged = onFullscreenChanged
        val container = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        customViewContainer = container
        contentRoot.addView(container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onFullscreenChanged(true)
    }

    fun hide() {
        val container = customViewContainer ?: return
        Log.i(TAG, "hide custom view (${context.javaClass.simpleName})")
        contentRoot.removeView(container)
        customViewContainer = null
        window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        callback?.onCustomViewHidden()
        callback = null
        onFullscreenChanged?.invoke(false)
        onFullscreenChanged = null
    }

    /** Returns true if a fullscreen custom view was showing and has now been dismissed by this call. */
    fun onBackPressed(): Boolean {
        if (!isShowing) return false
        hide()
        return true
    }
}
