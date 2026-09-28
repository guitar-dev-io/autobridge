package dev.autobridge.browser

import android.content.Context

/**
 * Keeps one [CarWebRenderer] alive for the whole car session.
 *
 * `CarBrowserScreen` is constructed fresh on every `screenManager.push(...)`, so a renderer owned by
 * the screen meant Home → Browser → Back → Browser built a new WebView and threw the page, its
 * history and its tabs away. Binding the renderer to the session instead makes "the WebView is
 * never recreated for a UI state change" hold across navigation as well as across chrome changes.
 *
 * The renderer is released by the session's own teardown (see `AutoBridgeSession`), not by a screen
 * going away, because a screen leaving is exactly the case whose state must survive.
 */
object CarBrowserRuntime {
    private var renderer: CarWebRenderer? = null

    /** The session-scoped renderer, created on first use. */
    fun renderer(context: Context): CarWebRenderer =
        renderer ?: CarWebRenderer(context).also { renderer = it }

    /** The renderer if one exists, without creating it; diagnostics must not start a browser. */
    fun rendererOrNull(): CarWebRenderer? = renderer

    /** Tears the renderer down. Called when the car session ends, never when a screen is popped. */
    fun release() {
        renderer?.destroy()
        renderer = null
    }
}
