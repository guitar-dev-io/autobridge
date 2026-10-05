package dev.autobridge.duoscreen.layout

/**
 * Pure pane-geometry math for Duo Screen's edit ("จัดหน้าจอ") mode: move, resize and snap. No
 * Android dependency, so it runs on the JVM without a device — same shape as
 * [dev.autobridge.input.PinchGeometry] (focus point + scale factor, the only multi-touch-shaped
 * signal androidx.car.app.SurfaceCallback.onScale exposes).
 *
 * Pixel coordinates, top-left origin, matching SurfaceContainer's own (width, height) convention.
 */
object DuoScreenLayout {
    const val MIN_WIDTH_FRACTION = 0.2f
    const val MIN_HEIGHT_FRACTION = 0.25f
    const val SNAP_THRESHOLD_PX = 24

    /** How far from a seam a tap still counts as grabbing it, rather than selecting a pane. */
    const val DIVIDER_GRAB_PX = 40

    /** How far two pane edges may sit apart and still be treated as one shared seam. */
    const val SEAM_TOLERANCE_PX = 8

    /** The band drawn under a grabbed seam, so the driver can see what the drag is moving. */
    const val DIVIDER_BAND_PX = 10

    data class Rect(val left: Int, val top: Int, val width: Int, val height: Int) {
        val right: Int get() = left + width
        val bottom: Int get() = top + height

        fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom
    }

    data class Bounds(val width: Int, val height: Int) {
        val minPaneWidth: Int get() = (width * MIN_WIDTH_FRACTION).toInt().coerceAtMost(width)
        val minPaneHeight: Int get() = (height * MIN_HEIGHT_FRACTION).toInt().coerceAtMost(height)
    }

    /** Clamps size to the pane minimum/[bounds], then clamps position so the pane stays inside. */
    fun clamp(rect: Rect, bounds: Bounds): Rect {
        if (bounds.width <= 0 || bounds.height <= 0) return rect
        val width = rect.width.coerceIn(bounds.minPaneWidth, bounds.width)
        val height = rect.height.coerceIn(bounds.minPaneHeight, bounds.height)
        val left = rect.left.coerceIn(0, bounds.width - width)
        val top = rect.top.coerceIn(0, bounds.height - height)
        return Rect(left, top, width, height)
    }

    /** Drag: moves without resizing, then clamps and snaps to the bounds' edges. */
    fun move(rect: Rect, dx: Int, dy: Int, bounds: Bounds): Rect {
        val moved = rect.copy(left = rect.left + dx, top = rect.top + dy)
        return snap(clamp(moved, bounds), bounds)
    }

    /**
     * Pinch: resizes around ([focusX], [focusY]) by [scaleFactor] (> 1 grows the pane), keeping the
     * point under the fingers stationary — the pane's edges move in proportion to how far the focus
     * point sits from them, not from its top-left corner alone.
     */
    fun resize(rect: Rect, focusX: Int, focusY: Int, scaleFactor: Float, bounds: Bounds): Rect {
        if (!scaleFactor.isFinite() || scaleFactor <= 0f) return rect
        val newWidth = (rect.width * scaleFactor).toInt()
        val newHeight = (rect.height * scaleFactor).toInt()
        val leftFraction = if (rect.width == 0) 0f else (focusX - rect.left).toFloat() / rect.width
        val topFraction = if (rect.height == 0) 0f else (focusY - rect.top).toFloat() / rect.height
        val newLeft = (focusX - leftFraction * newWidth).toInt()
        val newTop = (focusY - topFraction * newHeight).toInt()
        return snap(clamp(Rect(newLeft, newTop, newWidth, newHeight), bounds), bounds)
    }

    /** Snaps an edge to the bounds' edge once it is within [SNAP_THRESHOLD_PX] of it. */
    fun snap(rect: Rect, bounds: Bounds): Rect {
        var left = rect.left
        var top = rect.top
        if (left in 1..SNAP_THRESHOLD_PX) left = 0
        if (top in 1..SNAP_THRESHOLD_PX) top = 0
        val rightGap = bounds.width - (left + rect.width)
        if (rightGap in 1..SNAP_THRESHOLD_PX) left = bounds.width - rect.width
        val bottomGap = bounds.height - (top + rect.height)
        if (bottomGap in 1..SNAP_THRESHOLD_PX) top = bounds.height - rect.height
        return rect.copy(left = left, top = top)
    }

    /** True if [a] and [b] overlap; for the pane picker (tap = topmost pane under the finger). */
    fun overlaps(a: Rect, b: Rect): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    /**
     * Scales a pane-local point (surface pixels, measured from the pane's top-left) into the pane
     * display's own pixels.
     *
     * Subtracting the pane's origin is not enough on its own. The compositor draws a pane's
     * display as a quad at [rect], stretching whatever size that display is actually running at to
     * fit — and the two are equal only in the steady state. During a drag the rect moves every
     * frame while the display keeps its old size until the gesture settles, and a resize the
     * system refuses leaves them apart for good. Either way the image the driver aims at is
     * scaled, so the touch has to be scaled the same way or it lands beside what they tapped, by
     * exactly that ratio.
     */
    fun scaleToDisplay(
        localX: Int,
        localY: Int,
        rect: Rect,
        displayWidth: Int,
        displayHeight: Int
    ): Pair<Int, Int> {
        if (rect.width <= 0 || rect.height <= 0) return 0 to 0
        val x = localX.toLong() * displayWidth / rect.width
        val y = localY.toLong() * displayHeight / rect.height
        return x.toInt().coerceIn(0, (displayWidth - 1).coerceAtLeast(0)) to
            y.toInt().coerceIn(0, (displayHeight - 1).coerceAtLeast(0))
    }

    /**
     * [scaleToDisplay] for a distance rather than a point: same ratio, no clamping, because a
     * scrolled distance is signed and is not a position on the display.
     */
    fun scaleDeltaToDisplay(
        dx: Int,
        dy: Int,
        rect: Rect,
        displayWidth: Int,
        displayHeight: Int
    ): Pair<Int, Int> {
        if (rect.width <= 0 || rect.height <= 0) return 0 to 0
        return (dx.toLong() * displayWidth / rect.width).toInt() to
            (dy.toLong() * displayHeight / rect.height).toInt()
    }

    /** Which way a seam runs: [VERTICAL] separates panes left/right, [HORIZONTAL] top/bottom. */
    enum class Axis { VERTICAL, HORIZONTAL }

    /**
     * A seam shared by two neighbouring panes. [first] is the id of the pane on the left (or on
     * top) of it, [second] the one after it, so a positive drag always grows [first] at
     * [second]'s expense.
     *
     * [position] is the seam's coordinate on the [axis]; [from]/[to] are its extent along the other
     * one, i.e. the stretch the two panes actually share, which is what gets drawn and hit-tested.
     */
    data class Divider(
        val first: Int,
        val second: Int,
        val axis: Axis,
        val position: Int,
        val from: Int,
        val to: Int
    )

    /** The seam as a drawable rect, [DIVIDER_BAND_PX] thick and centred on it. */
    fun band(divider: Divider): Rect {
        val half = DIVIDER_BAND_PX / 2
        val length = (divider.to - divider.from).coerceAtLeast(1)
        return when (divider.axis) {
            Axis.VERTICAL -> Rect(divider.position - half, divider.from, DIVIDER_BAND_PX, length)
            Axis.HORIZONTAL -> Rect(divider.from, divider.position - half, length, DIVIDER_BAND_PX)
        }
    }

    /**
     * The seam between the pane [aId]/[a] and the pane [bId]/[b], or null when they are not
     * neighbours on this [axis] — their facing edges are more than [SEAM_TOLERANCE_PX] apart, or
     * they share no stretch of it. The two panes may be given in either order; the returned
     * divider always names the left (or upper) one first.
     */
    fun dividerBetween(aId: Int, a: Rect, bId: Int, b: Rect, axis: Axis): Divider? {
        val aIsFirst = when (axis) {
            Axis.VERTICAL -> a.left <= b.left
            Axis.HORIZONTAL -> a.top <= b.top
        }
        val first = if (aIsFirst) a else b
        val second = if (aIsFirst) b else a
        val (gap, position) = when (axis) {
            Axis.VERTICAL -> (second.left - first.right) to ((first.right + second.left) / 2)
            Axis.HORIZONTAL -> (second.top - first.bottom) to ((first.bottom + second.top) / 2)
        }
        if (kotlin.math.abs(gap) > SEAM_TOLERANCE_PX) return null
        val from = when (axis) {
            Axis.VERTICAL -> maxOf(first.top, second.top)
            Axis.HORIZONTAL -> maxOf(first.left, second.left)
        }
        val to = when (axis) {
            Axis.VERTICAL -> minOf(first.bottom, second.bottom)
            Axis.HORIZONTAL -> minOf(first.right, second.right)
        }
        if (from >= to) return null
        return Divider(
            first = if (aIsFirst) aId else bId,
            second = if (aIsFirst) bId else aId,
            axis = axis,
            position = position,
            from = from,
            to = to
        )
    }

    /** How far (in px) a tap at ([x], [y]) is from [divider], or null if it misses it along the seam. */
    fun distanceToDivider(divider: Divider, x: Int, y: Int): Int? {
        val (across, along) = when (divider.axis) {
            Axis.VERTICAL -> x to y
            Axis.HORIZONTAL -> y to x
        }
        if (along < divider.from || along >= divider.to) return null
        return kotlin.math.abs(across - divider.position)
    }

    /**
     * Drags the seam by [delta] px: the pane before it grows and the one after it shrinks by the
     * same amount, so the gap between them — and the surface they jointly cover — is unchanged.
     *
     * The move is clamped so neither pane falls under the minimum; a seam with no room left in the
     * requested direction simply returns the two rects as they were.
     */
    fun moveDivider(
        first: Rect,
        second: Rect,
        delta: Int,
        axis: Axis,
        bounds: Bounds
    ): Pair<Rect, Rect> {
        val (firstExtent, secondExtent, minExtent) = when (axis) {
            Axis.VERTICAL -> Triple(first.width, second.width, bounds.minPaneWidth)
            Axis.HORIZONTAL -> Triple(first.height, second.height, bounds.minPaneHeight)
        }
        // Clamped at zero on both ends: a pane that is already under the minimum (a restored
        // layout re-fitted onto a smaller panel) may still be grown, but nothing may squeeze it
        // further, and the bounds can never cross and send the seam the way it was not dragged.
        val lower = minOf(minExtent - firstExtent, 0)
        val upper = maxOf(secondExtent - minExtent, 0)
        val moved = delta.coerceIn(lower, upper)
        if (moved == 0) return first to second
        return when (axis) {
            Axis.VERTICAL -> first.copy(width = first.width + moved) to
                second.copy(left = second.left + moved, width = second.width - moved)
            Axis.HORIZONTAL -> first.copy(height = first.height + moved) to
                second.copy(top = second.top + moved, height = second.height - moved)
        }
    }
}
