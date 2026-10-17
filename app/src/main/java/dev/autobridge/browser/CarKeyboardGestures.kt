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

/**
 * Makes one physical double tap type at most two characters, whichever way the host reports it.
 *
 * The host's double tap is normally a lone `onScale`, replayed as two taps. If a host also sends an
 * `onClick` for the first tap just before that `onScale`, or for the second tap just after it,
 * replaying two would type the key three times. So an `onScale` double tap that follows an
 * unpaired `onClick` on the same spot within [PAIR_MS] types once (the click was tap one), and an
 * `onClick` on the same spot within [ECHO_MS] after a double tap types nothing (it was tap two).
 * "Same spot" is within [slop] on both axes; the renderer passes a key height.
 */
internal class CarKeyboardTapFilter {
    private var hasClick = false
    private var clickMs = 0L
    private var clickX = 0f
    private var clickY = 0f

    private var hasDouble = false
    private var doubleMs = 0L
    private var doubleX = 0f
    private var doubleY = 0f

    /** Taps an `onClick` at (x, y) types: 0 when it is the host echoing a double tap, else 1. */
    fun onClick(nowMs: Long, x: Float, y: Float, slop: Float): Int {
        if (hasDouble && nowMs - doubleMs in 0..ECHO_MS && near(x, y, doubleX, doubleY, slop)) {
            hasDouble = false
            return 0
        }
        hasDouble = false
        hasClick = true
        clickMs = nowMs
        clickX = x
        clickY = y
        return 1
    }

    /** Taps an `onScale` double tap at (x, y) types: 1 when its first tap already came as a click, else 2. */
    fun onDoubleTap(nowMs: Long, x: Float, y: Float, slop: Float): Int {
        val firstTyped = hasClick && nowMs - clickMs in 0..PAIR_MS && near(x, y, clickX, clickY, slop)
        hasClick = false
        hasDouble = true
        doubleMs = nowMs
        doubleX = x
        doubleY = y
        return if (firstTyped) 1 else 2
    }

    private fun near(x: Float, y: Float, ox: Float, oy: Float, slop: Float): Boolean =
        abs(x - ox) <= slop && abs(y - oy) <= slop

    companion object {
        /** Longest gap between a click and the `onScale` of the same double tap (host timeout ~300 ms). */
        const val PAIR_MS = 400L

        /** Longest gap between a double tap and a click the host sends for its second tap. */
        const val ECHO_MS = 120L
    }
}
