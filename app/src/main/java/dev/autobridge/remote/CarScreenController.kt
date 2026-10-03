package dev.autobridge.remote

import java.lang.ref.WeakReference

/**
 * Bridge that lets the process-global [AutoBridgeCommandRouter] drive the live Android Auto UI
 * WITHOUT the router depending on the car module internals or duplicating navigation.
 *
 * The car session registers a [Host] (for navigation) while connected, and the active
 * `CarBrowserScreen` registers itself as [activeBrowser] while on top. The router only asks this
 * controller to navigate or to reach the active browser; navigation itself is performed by the SAME
 * `screenManager.push(...)` the car UI already uses.
 *
 * When nothing is registered (Android Auto not connected), calls resolve to null so the router can
 * report NOT_CONNECTED gracefully instead of crashing.
 */
object CarScreenController {

    /** Implemented by the car session to navigate using the existing ScreenManager. */
    interface Host {
        /** Push a fresh browser screen; returns the target bound to it (registers as activeBrowser). */
        fun pushBrowser(): BrowserTarget
        fun pushMirror()
        fun pushMedia()
        /** Pushes the car's native video screen for [url], behind the same one-time disclaimer
         *  every other video entry point (library, IPTV) goes through. */
        fun pushVideo(url: String, title: String)

        /**
         * Brings up the bridge's minimal player
         * ([dev.autobridge.bridge.CarBridgePlayerScreen]) and leaves it on top.
         *
         * Separate from [pushVideo] because that one is the library's entry point and carries the
         * library's one-time disclaimer and its own URL; this is the surface the media bridge
         * draws into, whose content is whatever
         * [dev.autobridge.bridge.AutoBridgeSessionManager] currently holds.
         */
        fun pushBridgePlayer()
        fun pushAgent()
        fun popToHome()
        fun pushSettings()

        /** Shows a short, non-blocking confirmation on the car surface (CarToast). */
        fun showFeedback(message: String)
    }

    /**
     * Thin abstraction over CarBrowserScreen + its CarWebRenderer so the router can call browser
     * actions on whichever browser is live. Implemented by CarBrowserScreen.
     */
    interface BrowserTarget {
        fun openUrl(url: String)
        fun reload()
        fun goBack(): Boolean
        fun goForward(): Boolean
        fun setFullscreen(enabled: Boolean)
        fun setDesktopMode(enabled: Boolean)
        fun sendTextToSearch(text: String, autoSubmit: Boolean)
        val currentUrl: String
    }

    @Volatile
    private var hostRef: WeakReference<Host>? = null

    /** The browser screen currently on top, or null. Set by CarBrowserScreen's lifecycle. */
    @Volatile
    var activeBrowser: BrowserTarget? = null

    fun register(host: Host) {
        hostRef = WeakReference(host)
    }

    fun unregister(host: Host) {
        if (hostRef?.get() === host) hostRef = null
    }

    fun host(): Host? = hostRef?.get()

    /** Returns the live browser target, opening a fresh browser via [host] if none is active. */
    fun requireBrowser(): BrowserTarget? = activeBrowser ?: host()?.pushBrowser()

    val isConnected: Boolean get() = host() != null
}
