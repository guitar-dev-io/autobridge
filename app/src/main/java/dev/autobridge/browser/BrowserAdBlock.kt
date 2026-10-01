package dev.autobridge.browser

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.core.content.edit
import java.io.ByteArrayInputStream

/**
 * The browser's network-level ad and tracker blocker: the preference, and the one call every
 * `WebViewClient` in the app makes from `shouldInterceptRequest`.
 *
 * [AdBlockList] decides *what* is an ad; this object decides *whether* to act and what to answer
 * with. Both browser surfaces (the phone [BrowserActivity], the car [CarWebRenderer] including its
 * side pane, the projection browser and the entertainment player) route through here, so a page
 * behaves the same in the car as in the hand — the arrangement [dev.autobridge.youtube.YouTubeEnhancer]
 * already uses for the YouTube add-ons.
 *
 * ## Off by default
 *
 * Dropping requests changes what a site is able to serve, and a site may notice and ask the user to
 * turn it off. That is the user's call to make, not the app's, so this starts off for the same
 * reason [dev.autobridge.youtube.YouTubeSettings] starts off.
 *
 * ## Why the flag is cached
 *
 * `shouldInterceptRequest` is called on a Chromium worker thread for **every** subresource of every
 * page — hundreds per page load. Reading `SharedPreferences` that often would put a disk-backed
 * lookup on the critical path of each request, so the value is held in a `@Volatile` field that
 * [setEnabled] updates; the preference is read once, lazily, per process.
 */
object BrowserAdBlock {
    private const val PREFS_NAME = "autobridge_browser"
    private const val KEY_ENABLED = "adblock_enabled"

    /** Null until the preference has been read once. Written by [setEnabled] and by that first read. */
    @Volatile
    private var cached: Boolean? = null

    fun enabled(context: Context): Boolean =
        cached ?: prefs(context).getBoolean(KEY_ENABLED, false).also { cached = it }

    fun setEnabled(context: Context, value: Boolean) {
        cached = value
        prefs(context).edit { putBoolean(KEY_ENABLED, value) }
    }

    /**
     * What a `WebViewClient.shouldInterceptRequest` override should return: an empty reply for an
     * ad, or null to let Chromium fetch the request as usual.
     *
     * Main-frame navigations are never blocked. A URL the user typed, tapped or was redirected to is
     * a page they asked for, and an empty document in its place reads as a browser that is broken
     * rather than as an ad that was stopped. Only subresources — scripts, iframes, images, beacons —
     * are candidates.
     */
    fun intercept(context: Context, request: WebResourceRequest): WebResourceResponse? {
        if (request.isForMainFrame) return null
        if (!enabled(context)) return null
        if (!AdBlockList.blocks(request.url.toString())) return null
        return blocked()
    }

    /**
     * An empty 200 rather than an error.
     *
     * A failed request makes a page's own error handling run — retry loops, "please disable your ad
     * blocker" branches, and in a few cases a script that stops initialising the rest of the page. A
     * successful but empty reply is the quieter answer: the loader completes, and nothing is drawn.
     */
    private fun blocked(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
