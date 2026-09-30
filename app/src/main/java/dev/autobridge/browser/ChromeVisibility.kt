package dev.autobridge.browser

/**
 * Immersive-chrome state machine: whether the toolbar is shown, how opaque it is, and when idle
 * time should hide it.
 *
 * It is pure and clock-injected — no Handler, no View, no Android types — so every transition can
 * be asserted in a JVM test, and so the renderer can evaluate it inside its existing frame pump
 * instead of scheduling a second set of callbacks.
 *
 * Crucially, *nothing here resizes anything*. Show/hide is expressed only as [alphaAt], which the
 * renderer applies to the overlay it composites on top of an already-laid-out page. Animating
 * opacity rather than adding and removing chrome is what keeps the page's viewport, scroll offset
 * and layout untouched across every transition.
 */
class ChromeVisibility(
    private val autoHideAfterMs: Long = DEFAULT_AUTO_HIDE_MS,
    private val fadeDurationMs: Long = DEFAULT_FADE_MS,
) {
    companion object {
        const val DEFAULT_AUTO_HIDE_MS = 4_500L
        const val DEFAULT_FADE_MS = 180L
    }

    /**
     * Whether idle time is allowed to hide the chrome at all. Off means the toolbar is pinned by
     * preference ([BrowserControlsStore.alwaysShowUrlBar]) rather than by the current gesture, so
     * [tick] can never take it away.
     */
    var autoHideEnabled: Boolean = true
        private set

    /** Pinned chrome never auto-hides; the user asked for it and only the user takes it away. */
    var fullscreen: Boolean = false
        private set
    var drawerOpen: Boolean = false
        private set

    private var shown: Boolean = true
    private var lastInteractionMs: Long = 0L
    private var transitionStartMs: Long = 0L
    private var transitionFrom: Float = 1f

    /** Target opacity of the chrome, ignoring the in-flight fade. */
    private val target: Float get() = if (shown) 1f else 0f

    val isShown: Boolean get() = shown

    /** Records user activity and reveals the chrome unless fullscreen has been pinned. */
    fun onInteraction(nowMs: Long) {
        lastInteractionMs = nowMs
        if (!fullscreen) show(nowMs)
    }

    /** Explicit reveal, e.g. the fullscreen handle or an edge tap. */
    fun show(nowMs: Long) {
        lastInteractionMs = nowMs
        if (!shown) beginTransition(nowMs, true)
    }

    fun hide(nowMs: Long) {
        if (shown) beginTransition(nowMs, false)
    }

    /**
     * Applies the auto-hide preference. Turning it off reveals the chrome immediately: the setting
     * is "keep the address bar on screen", and leaving it hidden until the next tap would be the
     * opposite of what was asked for.
     */
    fun setAutoHide(nowMs: Long, enabled: Boolean) {
        autoHideEnabled = enabled
        if (!enabled) show(nowMs)
    }

    fun setFullscreen(nowMs: Long, enabled: Boolean) {
        fullscreen = enabled
        if (enabled) hide(nowMs) else show(nowMs)
    }

    fun toggleFullscreen(nowMs: Long) = setFullscreen(nowMs, !fullscreen)

    fun setDrawerOpen(nowMs: Long, open: Boolean) {
        drawerOpen = open
        // An open drawer implies the user is navigating chrome, so the toolbar stays with it.
        if (open) show(nowMs) else lastInteractionMs = nowMs
    }

    /**
     * Advances idle timing. Returns true when the visible state changed and the surface needs a
     * repaint. Auto-hide is suspended while the drawer is open and while chrome is already hidden.
     *
     * Fullscreen does **not** suspend it. Chrome recalled by the handle while fullscreen is pinned
     * must fade away again on its own, otherwise the toolbar recalled once stays over the page for
     * the rest of the session — observed on a head unit as `chrome=shown fullscreen=true` never
     * returning to hidden.
     *
     * [autoHideEnabled] does suspend it, because that one is a standing preference rather than a
     * transient state.
     */
    fun tick(nowMs: Long): Boolean {
        if (!shown || drawerOpen || !autoHideEnabled) return false
        // Seed the idle clock on the first tick. lastInteractionMs starts at 0 while the caller's
        // clock is an uptime in the millions, so without this the very first tick saw an "idle" of
        // the whole uptime and hid the toolbar a few frames after the browser opened.
        if (lastInteractionMs == 0L) {
            lastInteractionMs = nowMs
            return false
        }
        if (nowMs - lastInteractionMs < autoHideAfterMs) return false
        beginTransition(nowMs, false)
        return true
    }

    /** Current chrome opacity, including the in-flight fade. */
    fun alphaAt(nowMs: Long): Float {
        if (fadeDurationMs <= 0L) return target
        val elapsed = nowMs - transitionStartMs
        if (elapsed >= fadeDurationMs || elapsed < 0L) return target
        val progress = elapsed.toFloat() / fadeDurationMs
        return transitionFrom + (target - transitionFrom) * progress
    }

    /** True while a fade is still running, so the pump knows to keep drawing. */
    fun isAnimating(nowMs: Long): Boolean =
        fadeDurationMs > 0L && (nowMs - transitionStartMs) in 0 until fadeDurationMs

    /** Chrome is drawn (and hit-tested) while it is visible or still fading out. */
    fun isRendered(nowMs: Long): Boolean = alphaAt(nowMs) > 0.01f

    private fun beginTransition(nowMs: Long, toShown: Boolean) {
        transitionFrom = alphaAt(nowMs)
        transitionStartMs = nowMs
        shown = toShown
    }
}
