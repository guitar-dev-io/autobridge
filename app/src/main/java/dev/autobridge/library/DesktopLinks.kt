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

    /** Whether Chrome for Linux can be used here: not in this build, not installed yet, or ready. */
    enum class ChromeStatus { UNAVAILABLE, MISSING, READY }

    /** Set by the sideload flavors; null (the safe build) means [ChromeStatus.UNAVAILABLE]. */
    @Volatile
    var status: ((Context) -> ChromeStatus)? = null

    /** Opens the guided Chrome setup on the phone; set by the sideload flavors. */
    @Volatile
    var setup: ((Context) -> Unit)? = null

    fun chromeStatus(context: Context): ChromeStatus = status?.invoke(context) ?: ChromeStatus.UNAVAILABLE

    /** True when the guided setup was opened; false in a build that has none. */
    fun openSetup(context: Context): Boolean = setup?.let { it(context); true } ?: false

    /** True when [url] was handed to the desktop browser; false means open it the usual way. */
    fun open(context: Context, url: String): Boolean = opener?.invoke(context, url) ?: false

    /** Whether the catalog site at [url] is one that should go to the desktop browser: the ones that may not play in the WebView. */
    fun wantsDesktop(url: String): Boolean =
        StreamingLinks.all.firstOrNull { it.url == url }?.let { StreamingLinks.mayNotPlay(it) } == true
}
