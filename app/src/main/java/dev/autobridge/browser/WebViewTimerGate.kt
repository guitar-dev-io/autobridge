package dev.autobridge.browser

import android.os.Looper
import android.util.Log
import android.webkit.WebView

/**
 * Process-wide owner of `WebView.pauseTimers()` / `resumeTimers()`.
 *
 * Both calls are global: they suspend layout, parsing and JavaScript timers for *every* WebView in
 * the process, not just the one they are called on. The app has three independent WebView surfaces
 * (the car browser, the phone [BrowserActivity] and the entertainment player), so no one surface may
 * pause timers on its own — the car browser going idle would freeze a page the user is reading on
 * the phone.
 *
 * Each surface [hold]s the gate while it needs live pages and [release]s it when it can be
 * suspended. Timers are paused only once nobody holds the gate, and resumed as soon as anyone takes
 * it again. Timers start running (the WebView default), so a surface that never touches the gate is
 * never affected until another one releases.
 *
 * Main thread only, like every WebView call.
 */
object WebViewTimerGate {
    private const val TAG = "AutoBridgeWebTimers"

    private val holders = mutableSetOf<String>()
    private var paused = false

    /** Marks [owner] as needing live pages; resumes timers if they were paused. */
    fun hold(owner: String, view: WebView) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "WebViewTimerGate is main-thread only" }
        holders += owner
        if (!paused) return
        runCatching { view.resumeTimers() }
            .onSuccess {
                paused = false
                Log.i(TAG, "WebView timers resumed by $owner")
            }
            .onFailure { Log.w(TAG, "resumeTimers failed for $owner", it) }
    }

    /**
     * Drops [owner]'s claim. When nobody holds the gate any more, timers are paused through [view],
     * which must still be alive (call this before `WebView.destroy()`).
     */
    fun release(owner: String, view: WebView) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "WebViewTimerGate is main-thread only" }
        holders -= owner
        if (paused || holders.isNotEmpty()) return
        runCatching { view.pauseTimers() }
            .onSuccess {
                paused = true
                Log.i(TAG, "WebView timers paused (last holder: $owner)")
            }
            .onFailure { Log.w(TAG, "pauseTimers failed for $owner", it) }
    }
}
