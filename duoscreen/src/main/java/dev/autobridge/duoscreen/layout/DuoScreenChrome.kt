package dev.autobridge.duoscreen.layout

import dev.autobridge.duoscreen.layout.DuoScreenLayout.Bounds
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Rect
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How the panes are *shown*, as opposed to where they are (docs/design/13, DuoPortrait).
 *
 * The pane set keeps its rects edge to edge: that is what seams, seam drags, presets and the
 * saved layout are all built on. What the driver sees is each pane drawn a little smaller — a
 * margin from the surface edge, and a gutter on every seam that the control bar sits in — with
 * rounded corners. The pane's display is sized to this visual rect, so nothing is stretched, and
 * taps are mapped through it.
 *
 * All sizes come from the design, which was drawn on a 1074 px wide portrait surface, and scale
 * with the surface's short side so the bar and the gutter keep the same proportion everywhere.
 */
object DuoScreenChrome {
    /** The short side the design was drawn at. */
    const val DESIGN_SHORT_SIDE = 1074f
    const val MIN_SCALE = 0.6f
    const val MAX_SCALE = 1.6f

    const val OUTER_MARGIN = 12f
    const val SEAM_GUTTER = 72f
    const val CORNER_RADIUS = 20f

    fun scale(bounds: Bounds): Float {
        val short = minOf(bounds.width, bounds.height)
        if (short <= 0) return 1f
        return (short / DESIGN_SHORT_SIDE).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    fun px(designPx: Float, bounds: Bounds): Int = (designPx * scale(bounds)).roundToInt()

    fun gutter(bounds: Bounds): Int = px(SEAM_GUTTER, bounds)

    fun cornerRadius(bounds: Bounds): Int = px(CORNER_RADIUS, bounds)

    /**
     * Each pane's visual rect: every edge on the surface border moves in by the outer margin,
     * every edge on a seam by half the gutter, and an edge that is neither (a picture-in-picture
     * tile floating over another pane) stays where it is. Never smaller than 1 x 1.
     */
    fun visualRects(panes: List<DuoScreenPane>, bounds: Bounds): Map<Int, Rect> {
        val outer = px(OUTER_MARGIN, bounds)
        val half = gutter(bounds) / 2
        val tolerance = DuoScreenLayout.SEAM_TOLERANCE_PX
        return panes.associate { pane ->
            val r = pane.rect
            val others = panes.filter { it.id != pane.id }.map { it.rect }

            fun sharesLeft() = others.any { o -> abs(o.right - r.left) <= tolerance && spansY(o, r) }
            fun sharesRight() = others.any { o -> abs(o.left - r.right) <= tolerance && spansY(o, r) }
            fun sharesTop() = others.any { o -> abs(o.bottom - r.top) <= tolerance && spansX(o, r) }
            fun sharesBottom() = others.any { o -> abs(o.top - r.bottom) <= tolerance && spansX(o, r) }

            val left = when {
                r.left <= tolerance -> outer
                sharesLeft() -> half
                else -> 0
            }
            val top = when {
                r.top <= tolerance -> outer
                sharesTop() -> half
                else -> 0
            }
            val right = when {
                bounds.width - r.right <= tolerance -> outer
                sharesRight() -> half
                else -> 0
            }
            val bottom = when {
                bounds.height - r.bottom <= tolerance -> outer
                sharesBottom() -> half
                else -> 0
            }
            val width = (r.width - left - right).coerceAtLeast(1)
            val height = (r.height - top - bottom).coerceAtLeast(1)
            pane.id to Rect(r.left + left, r.top + top, width, height)
        }
    }

    private fun spansX(a: Rect, b: Rect) = minOf(a.right, b.right) > maxOf(a.left, b.left)
    private fun spansY(a: Rect, b: Rect) = minOf(a.bottom, b.bottom) > maxOf(a.top, b.top)
}
