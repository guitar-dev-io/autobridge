package dev.autobridge.browser

import android.app.Activity
import android.graphics.Color
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.widget.FrameLayout

/**
 * Hosts a WebView's HTML5 fullscreen custom view (e.g. a `<video>` element gone fullscreen,
 * including EME/Widevine playback) inside [contentRoot]. This is the container
 * `WebChromeClient.onShowCustomView`/`onHideCustomView` need — without one, a page's own
 * fullscreen request has nowhere to render and silently does nothing.
 */
class FullscreenVideoController(
    private val activity: Activity,
    private val contentRoot: FrameLayout,
) {
    private companion object {
        const val TAG = "[AutoBridge/Fullscreen]"
    }

    private var customViewContainer: FrameLayout? = null
    private var callback: WebChromeClient.CustomViewCallback? = null
    private var onFullscreenChanged: ((Boolean) -> Unit)? = null

    val isShowing: Boolean get() = customViewContainer != null

    fun show(view: View, callback: WebChromeClient.CustomViewCallback, onFullscreenChanged: (Boolean) -> Unit) {
        if (customViewContainer != null) {
            // Chromium's contract: a second onShowCustomView before the first is hidden must be
            // rejected by immediately invoking the new callback's onCustomViewHidden().
            callback.onCustomViewHidden()
            return
        }
        Log.i(TAG, "show custom view (${activity.javaClass.simpleName})")
        this.callback = callback
        this.onFullscreenChanged = onFullscreenChanged
        val container = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        customViewContainer = container
        contentRoot.addView(container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onFullscreenChanged(true)
    }

    fun hide() {
        val container = customViewContainer ?: return
        Log.i(TAG, "hide custom view (${activity.javaClass.simpleName})")
        contentRoot.removeView(container)
        customViewContainer = null
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
