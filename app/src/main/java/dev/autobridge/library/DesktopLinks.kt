package dev.autobridge.library

import android.content.Context

/**
 * Opens a site in Google Chrome for Linux (the sideload flavors' desktop engine) when it is
 * installed. Chrome specifically: Brave, or whatever the Web card is set to, is not used for these.
 *
 * Main code cannot name the engine: it exists only in the sideload flavors. Those set [opener]
 * at process start ([dev.autobridge.car.CarExtensions.install]); the safe flavor leaves it null,
 * so [open] is false there and the caller uses the built-in browser as before.
 */
object DesktopLinks {
    @Volatile
    var opener: ((Context, String) -> Boolean)? = null

    /** True when [url] was handed to the desktop browser; false means open it the usual way. */
    fun open(context: Context, url: String): Boolean = opener?.invoke(context, url) ?: false

    /** Whether the catalog site at [url] is one that should go to the desktop browser: the ones that may not play in the WebView. */
    fun wantsDesktop(url: String): Boolean =
        StreamingLinks.all.firstOrNull { it.url == url }?.let { StreamingLinks.mayNotPlay(it) } == true
}
