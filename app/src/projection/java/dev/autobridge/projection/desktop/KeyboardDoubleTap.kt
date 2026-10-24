package dev.autobridge.projection.desktop

import kotlin.math.hypot

/**
 * Recovers fast key taps on the car's drawn keyboard. The host turns two quick taps into one
 * `onScale` (a double-tap zoom), so a fast typist loses keys. While the keyboard is open, an
 * `onScale` is read as a double tap only when it looks like one: isolated (no `onScale` in the
 * [ISOLATION_MS] before it), a big jump in factor (>= [FACTOR_UP] or <= [FACTOR_DOWN]) and its
 * focus on the tray. The decision waits [DECIDE_MS]: another `onScale` in that window means a
 * pinch, so the candidate is dropped, and the rest of that stream (until [ISOLATION_MS] of
 * silence) is ignored. A confirmed double tap replays at the focus: once if the host already
 * delivered the first tap as an `onClick` near it ([CLICK_WINDOW_MS], [CLICK_SLOP_DP]), else twice.
 *
 * Pure: the clock, the main-thread scheduler and the tray test are injected. Main thread only.
 */
internal class KeyboardDoubleTap(
    private val clock: () -> Long,
    /** Runs the block after the delay; returns what cancels it. */
    private val schedule: (delayMs: Long, block: () -> Unit) -> (() -> Unit),
    /** Surface pixels per dp. */
    private val density: () -> Float,
    private val onTray: (x: Float, y: Float) -> Boolean,
    /** A key tap at (x, y), as if the host had delivered it. */
    private val replay: (x: Float, y: Float) -> Unit,
) {
    private var lastScaleAt = NEVER
    private var lastClickAt = NEVER
    private var lastClickX = 0f
    private var lastClickY = 0f
    private var cancelPending: (() -> Unit)? = null

    /** A tap the host delivered to the keyboard (not a replayed one). */
    fun onClick(x: Float, y: Float) {
        lastClickAt = clock()
        lastClickX = x
        lastClickY = y
    }

    fun onScale(focusX: Float, focusY: Float, factor: Float) {
        val now = clock()
        val previous = lastScaleAt
        lastScaleAt = now
        cancelPending?.let {
            // A second onScale while deciding: a pinch, not a double tap.
            it()
            cancelPending = null
            return
        }
        if (previous != NEVER && now - previous < ISOLATION_MS) return
        if (factor < FACTOR_UP && factor > FACTOR_DOWN) return
        if (!onTray(focusX, focusY)) return
        cancelPending = schedule(DECIDE_MS) {
            cancelPending = null
            confirm(focusX, focusY, now)
        }
    }

    /** Drops a pending decision (the keyboard closed). */
    fun cancel() {
        cancelPending?.invoke()
        cancelPending = null
    }

    private fun confirm(x: Float, y: Float, scaleAt: Long) {
        val slop = CLICK_SLOP_DP * density()
        val firstDelivered = lastClickAt != NEVER && scaleAt - lastClickAt in 0..CLICK_WINDOW_MS &&
            hypot(x - lastClickX, y - lastClickY) <= slop
        lastClickAt = NEVER
        replay(x, y)
        if (!firstDelivered) replay(x, y)
    }

    companion object {
        const val ISOLATION_MS = 300L
        const val DECIDE_MS = 120L
        const val CLICK_WINDOW_MS = 400L
        const val CLICK_SLOP_DP = 24f
        const val FACTOR_UP = 1.8f
        const val FACTOR_DOWN = 0.55f
        private const val NEVER = Long.MIN_VALUE
    }
}
