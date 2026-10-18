package dev.autobridge.desktop.display

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fits the X display into a target surface (letterboxed, aspect preserved) and maps touch points
 * back. One mapping serves both directions so a tap always lands on the pixel that was drawn
 * under the finger, on the phone and on the car alike.
 *
 * [area] is the part of the surface the page may use (the car host's stable/visible area); the
 * display is centred in it.
 */
data class DisplayMapping(
    val displayWidth: Int,
    val displayHeight: Int,
    val surfaceWidth: Int,
    val surfaceHeight: Int,
    val areaLeft: Int = 0,
    val areaTop: Int = 0,
    val areaRight: Int = surfaceWidth,
    val areaBottom: Int = surfaceHeight,
) {
    val scale: Float = min(
        (areaRight - areaLeft).toFloat() / displayWidth,
        (areaBottom - areaTop).toFloat() / displayHeight,
    ).coerceAtLeast(0.0001f)
    val drawWidth: Float = displayWidth * scale
    val drawHeight: Float = displayHeight * scale
    val offsetX: Float = areaLeft + ((areaRight - areaLeft) - drawWidth) / 2f
    val offsetY: Float = areaTop + ((areaBottom - areaTop) - drawHeight) / 2f

    /** Surface point → X display pixel, clamped to the display. */
    fun toDisplay(x: Float, y: Float): Pair<Int, Int> {
        val dx = ((x - offsetX) / scale).roundToInt().coerceIn(0, displayWidth - 1)
        val dy = ((y - offsetY) / scale).roundToInt().coerceIn(0, displayHeight - 1)
        return dx to dy
    }

    /** True when the point is on the drawn picture rather than the letterbox bars. */
    fun contains(x: Float, y: Float): Boolean =
        x >= offsetX && x < offsetX + drawWidth && y >= offsetY && y < offsetY + drawHeight

    /** Surface distance → display distance (for scroll deltas). */
    fun toDisplayDistance(d: Float): Float = d / scale
}
