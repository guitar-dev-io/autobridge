package dev.autobridge.browser

import kotlin.math.abs

/**
 * Android Auto reports a double tap on the surface as a single `onScale` with a large factor,
 * never as two `onClick`s. While the car keyboard is open that would zoom the page and lose both
 * keystrokes, so the renderer asks this whether an `onScale` is really a double tap on the tray.
 *
 * A pinch is a stream of factors close to 1; the host's double tap is one event far from 1.
 */
internal object CarKeyboardGestures {
    /** Minimum |factor - 1| for an `onScale` to count as a double tap rather than a pinch step. */
    const val DOUBLE_TAP_MIN_DELTA = 0.3f

    fun isKeyboardDoubleTap(focusX: Float, focusY: Float, factor: Float, tray: Box): Boolean {
        if (focusX < 0f || focusY < 0f) return false
        if (!factor.isFinite()) return false
        if (!tray.contains(focusX, focusY)) return false
        return abs(factor - 1f) >= DOUBLE_TAP_MIN_DELTA
    }
}
