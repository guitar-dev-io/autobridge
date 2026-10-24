package dev.autobridge.projection.desktop

/**
 * Turns a host double-tap on the page into a double-click, while a real pinch still zooms. With no
 * keyboard open, the car host delivers a double-tap as a single [onScale] with a big factor jump
 * (>= [FACTOR_UP] or <= [FACTOR_DOWN]) that is isolated (no [onScale] in the [ISOLATION_MS] before
 * it). A genuine pinch is a run of near-1.0 factors arriving close together: the first is forwarded
 * straight to [zoom], and a big factor that is followed by another [onScale] inside [DECIDE_MS] is
 * read as part of that stream and dropped, never double-clicked.
 *
 * Mirrors [KeyboardDoubleTap]'s pure, injected-clock/scheduler design, but replays a double-click
 * at the focus point instead of a key tap and has no tray test (a page double-tap is always two
 * clicks).
 *
 * Pure: the clock and the main-thread scheduler are injected. Main thread only.
 */
internal class PageDoubleTap(
    private val clock: () -> Long,
    /** Runs the block after the delay; returns what cancels it. */
    private val schedule: (delayMs: Long, block: () -> Unit) -> (() -> Unit),
    /** A double-click at the focus point, as a confirmed double-tap. */
    private val doubleClick: (x: Float, y: Float) -> Unit,
    /** A near-1.0 pinch factor at the focus point: zoom the page as before. */
    private val zoom: (x: Float, y: Float, factor: Float) -> Unit,
) {
    private var lastScaleAt = NEVER
    private var cancelPending: (() -> Unit)? = null

    fun onScale(focusX: Float, focusY: Float, factor: Float) {
        val now = clock()
        val previous = lastScaleAt
        lastScaleAt = now
        cancelPending?.let {
            // A second onScale while deciding: a pinch stream, not a double tap.
            it()
            cancelPending = null
            zoom(focusX, focusY, factor)
            return
        }
        val bigJump = factor >= FACTOR_UP || factor <= FACTOR_DOWN
        if (!bigJump) {
            // A near-1.0 factor is an ordinary pinch step: zoom.
            zoom(focusX, focusY, factor)
            return
        }
        if (previous != NEVER && now - previous < ISOLATION_MS) {
            // Not isolated: part of a pinch stream. Zoom rather than double-click.
            zoom(focusX, focusY, factor)
            return
        }
        cancelPending = schedule(DECIDE_MS) {
            cancelPending = null
            doubleClick(focusX, focusY)
        }
    }

    /** Drops a pending decision (the screen is going away). */
    fun cancel() {
        cancelPending?.invoke()
        cancelPending = null
    }

    companion object {
        const val ISOLATION_MS = 300L
        const val DECIDE_MS = 120L
        const val FACTOR_UP = 1.8f
        const val FACTOR_DOWN = 0.55f
        private const val NEVER = Long.MIN_VALUE
    }
}
