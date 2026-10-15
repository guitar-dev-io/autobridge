package dev.autobridge.browser
import kotlin.math.abs

/** A car-surface input event, as `SurfaceCallback` reports it. */
internal enum class CarSurfaceGesture { TAP, SCROLL, FLING, SCALE }

/**
 * Routing rules for car-surface input while the car keyboard is open.
 *
 * - The keyboard owns the whole band at and below the tray's top edge, edge to edge, including the
 *   key gaps, padding, field row, ✕, ⌄ and the surface margin/insets beside and below the tray. The
 *   tray is always the full viewport width and flush with its bottom, so this removes the half-open
 *   edge band where a missed bottom-row key used to close the keyboard.
 * - While the keyboard is open the page receives nothing: taps go to the keyboard (inside: keys;
 *   above: close), scroll and fling carry no position so they can't be shown to start outside the
 *   tray and are swallowed, and a scale never zooms the page.
 *
 * Android Auto also reports a double tap on the surface as a single `onScale` with a large factor,
 * never as two `onClick`s; [isKeyboardDoubleTap] tells that apart from a pinch so the renderer can
 * replay it as two keystrokes. A pinch is a stream of factors close to 1; the host's double tap is
 * one event far from 1.
 */
internal object CarKeyboardGestures {
    /** Minimum |factor - 1| for an `onScale` to count as a double tap rather than a pinch step. */
    const val DOUBLE_TAP_MIN_DELTA = 0.3f

    /**
     * True when (x, y), in car-surface pixels (the space the tray is drawn in), belongs to the open
     * keyboard: anywhere at or below the tray's top edge. False when the keyboard is closed
     * (tray == null), or the point is non-finite or negative (the "no focus" sentinel).
     */
    fun inKeyboardArea(x: Float, y: Float, tray: Box?): Boolean {
        if (tray == null) return false
        if (!x.isFinite() || !y.isFinite()) return false
        if (x < 0f || y < 0f) return false
        return y >= tray.top
    }

    /**
     * Whether the page (WebView, chrome under the tray, divider, drawer) may receive this event.
     * Keyboard closed (tray == null): always true. Keyboard open: always false. x/y are null for
     * SCROLL and FLING, which the host reports without a position.
     */
    @Suppress("UNUSED_PARAMETER")
    fun pageReceives(gesture: CarSurfaceGesture, x: Float?, y: Float?, tray: Box?): Boolean = tray == null

    fun isKeyboardDoubleTap(focusX: Float, focusY: Float, factor: Float, tray: Box): Boolean {
        if (focusX < 0f || focusY < 0f) return false
        if (!factor.isFinite()) return false
        if (!inKeyboardArea(focusX, focusY, tray)) return false
        return abs(factor - 1f) >= DOUBLE_TAP_MIN_DELTA
    }
}
