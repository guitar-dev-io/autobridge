package dev.autobridge.duoscreen.input

import dev.autobridge.duoscreen.layout.DuoScreenLayout
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Axis
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Bounds
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Divider
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Rect
import dev.autobridge.duoscreen.layout.DuoScreenPaneSet

/**
 * Where a routed touch or a layout change actually goes. Kept separate from
 * [DuoScreenShizukuBackend] so this router has no Android/Shizuku dependency and runs on the JVM;
 * [dev.autobridge.duoscreen.car.DuoScreenScreen] is the real implementation, mapping pane ids to
 * VirtualDisplay ids and calling the Shizuku backend.
 */
interface DuoScreenInputPort {
    /** Coordinates are pane-local (already offset by the pane's rect), matching what a VirtualDisplay-hosted app expects. */
    fun forwardTap(paneId: Int, localX: Int, localY: Int)
    fun forwardScroll(paneId: Int, dx: Int, dy: Int)
    fun onSelectionChanged(paneId: Int?)
    fun onPaneRectChanged(paneId: Int, rect: Rect)

    /** The seam the next drag will move, or null once it is let go. Drawn as the grab affordance. */
    fun onDividerGrabbed(divider: Divider?)
}

/**
 * Normal mode forwards touches to whichever pane the app last touched (androidx.car.app's
 * onScroll/onFling carry no position — see SurfaceCallback — so there is nothing else to route by).
 * Edit ("จัดหน้าจอ") mode repurposes the same gestures: tap selects a pane and brings it to front,
 * drag moves the selected pane, pinch resizes it around the focus point.
 *
 * Dragging the seam between two panes — the one gesture that resizes both at once, and the one a
 * driver expects from a split view — is the same two steps: a tap within
 * [DuoScreenLayout.DIVIDER_GRAB_PX] of a seam grabs it instead of selecting a pane, and the drags
 * that follow slide it. It has to be a grab-then-drag rather than a single drag because the host
 * reports a scroll as a bare distance with no position (SurfaceCallback again), so the tap is the
 * only thing that can say *which* seam is being dragged.
 */
class DuoScreenInputRouter(
    initialPanes: DuoScreenPaneSet,
    private val bounds: Bounds,
    private val port: DuoScreenInputPort
) {
    enum class Mode { NORMAL, EDIT }

    var mode: Mode = Mode.NORMAL
        private set

    var panes: DuoScreenPaneSet = initialPanes
        private set

    private var lastTouchedPaneId: Int? = null
    private var selectedPaneId: Int? = null
    private var grabbedDivider: Divider? = null

    /** The seam the next drag moves, for callers that want to show what is grabbed. */
    val divider: Divider? get() = grabbedDivider

    fun setMode(newMode: Mode) {
        if (mode == newMode) return
        mode = newMode
        if (newMode == Mode.NORMAL) {
            releaseDivider()
            if (selectedPaneId != null) {
                selectedPaneId = null
                port.onSelectionChanged(null)
            }
        }
    }

    fun onClick(x: Int, y: Int) {
        if (mode == Mode.EDIT) {
            val seam = panes.dividerAt(x, y)
            if (seam != null) {
                grabbedDivider = seam
                // A grabbed seam owns the drag, so nothing stays selected underneath it.
                selectedPaneId = null
                port.onSelectionChanged(null)
                port.onDividerGrabbed(seam)
                return
            }
        }
        val pane = panes.paneAt(x, y) ?: return
        when (mode) {
            Mode.NORMAL -> {
                lastTouchedPaneId = pane.id
                port.forwardTap(pane.id, x - pane.rect.left, y - pane.rect.top)
            }
            Mode.EDIT -> {
                releaseDivider()
                selectedPaneId = pane.id
                panes = panes.bringToFront(pane.id)
                port.onSelectionChanged(pane.id)
            }
        }
    }

    /**
     * Replaces a pane's rect outright, for a layout applied from outside the gesture stream (a
     * preset). The rect is clamped to [bounds] like any dragged one, and a grabbed seam is let go
     * because the arrangement it referred to no longer exists.
     */
    fun setRect(paneId: Int, rect: Rect) {
        if (panes.pane(paneId) == null) return
        releaseDivider()
        val clamped = DuoScreenLayout.clamp(rect, bounds)
        panes = panes.withRect(paneId, clamped)
        port.onPaneRectChanged(paneId, clamped)
    }

    /** Points a pane at another app, for a choice made on the phone while the session is live. */
    fun setPackage(paneId: Int, packageName: String?) {
        panes = panes.withPackage(paneId, packageName)
    }

    /** Raises a pane in the set's z-order without selecting it, for a layout applied from outside. */
    fun bringToFront(paneId: Int) {
        panes = panes.bringToFront(paneId)
    }

    private fun releaseDivider() {
        if (grabbedDivider == null) return
        grabbedDivider = null
        port.onDividerGrabbed(null)
    }

    /**
     * [dx]/[dy] are the distance *scrolled*, the host's own convention (SurfaceCallback, and
     * GestureDetector before it): the finger moving right reports a negative dx. Normal mode
     * forwards that as-is; edit mode negates it, because a pane or a seam being dragged has to
     * travel the way the finger went, not the way the content would have.
     */
    fun onScroll(dx: Int, dy: Int) {
        when (mode) {
            Mode.NORMAL -> {
                val id = lastTouchedPaneId ?: return
                port.forwardScroll(id, dx, dy)
            }
            Mode.EDIT -> dragOrMove(-dx, -dy)
        }
    }

    /**
     * Same /8 damping MirrorCarScreen already applies to onFling before treating it as a scroll.
     *
     * A fling's velocity points the way the finger went — the opposite sense to [onScroll]'s
     * distance — so edit mode uses it as the drag delta directly. Normal mode keeps forwarding it
     * as a scroll distance, exactly as MirrorCarScreen does.
     */
    fun onFling(velocityX: Int, velocityY: Int) {
        if (mode == Mode.NORMAL) {
            val id = lastTouchedPaneId ?: return
            port.forwardScroll(id, velocityX / 8, velocityY / 8)
        } else {
            dragOrMove(velocityX / 8, velocityY / 8)
        }
    }

    fun onScale(focusX: Int, focusY: Int, scaleFactor: Float) {
        if (mode != Mode.EDIT) return
        val id = selectedPaneId ?: return
        val pane = panes.pane(id) ?: return
        val resized = DuoScreenLayout.resize(pane.rect, focusX, focusY, scaleFactor, bounds)
        panes = panes.withRect(id, resized)
        port.onPaneRectChanged(id, resized)
    }

    /** A drag in edit mode slides the grabbed seam if there is one, otherwise the selected pane. */
    private fun dragOrMove(dx: Int, dy: Int) {
        if (grabbedDivider != null) dragDivider(dx, dy) else moveSelected(dx, dy)
    }

    private fun dragDivider(dx: Int, dy: Int) {
        val seam = grabbedDivider ?: return
        val first = panes.pane(seam.first)
        val second = panes.pane(seam.second)
        if (first == null || second == null) {
            releaseDivider()
            return
        }
        val delta = if (seam.axis == Axis.VERTICAL) dx else dy
        if (delta == 0) return
        val (movedFirst, movedSecond) =
            DuoScreenLayout.moveDivider(first.rect, second.rect, delta, seam.axis, bounds)
        if (movedFirst == first.rect && movedSecond == second.rect) return
        panes = panes.withRect(seam.first, movedFirst).withRect(seam.second, movedSecond)
        port.onPaneRectChanged(seam.first, movedFirst)
        port.onPaneRectChanged(seam.second, movedSecond)
        // The seam moved with the panes, so the next drag — and the band drawn under it — follow it.
        grabbedDivider = DuoScreenLayout.dividerBetween(
            seam.first, movedFirst, seam.second, movedSecond, seam.axis
        ) ?: seam
        port.onDividerGrabbed(grabbedDivider)
    }

    private fun moveSelected(dx: Int, dy: Int) {
        val id = selectedPaneId ?: return
        val pane = panes.pane(id) ?: return
        val moved = DuoScreenLayout.move(pane.rect, dx, dy, bounds)
        panes = panes.withRect(id, moved)
        port.onPaneRectChanged(id, moved)
    }
}
