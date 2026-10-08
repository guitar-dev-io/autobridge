package dev.autobridge.duoscreen.layout

import dev.autobridge.duoscreen.layout.DuoScreenLayout.Axis
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Bounds
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Divider
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Rect

/** One pane: its stable id (maps to a VirtualDisplay id once one exists), app, and current rect. */
data class DuoScreenPane(
    val id: Int,
    val packageName: String?,
    val rect: Rect
)

/** 2-3 even columns across [bounds], left to right. The spec's starting layout before any drag/resize. */
object DuoScreenPaneLayouts {
    fun evenColumns(paneCount: Int, bounds: Bounds): List<Rect> {
        require(paneCount in 2..3) { "Duo Screen supports 2-3 panes, got $paneCount" }
        val columnWidth = bounds.width / paneCount
        return (0 until paneCount).map { index ->
            val left = index * columnWidth
            // The last column absorbs the rounding remainder so the columns tile bounds.width
            // exactly instead of leaving a sliver of uncovered car surface on the right edge.
            val width = if (index == paneCount - 1) bounds.width - left else columnWidth
            Rect(left, 0, width, bounds.height)
        }
    }
}

/**
 * Immutable set of panes in z-order (last = topmost = drawn on top and hit-tested first), plus the
 * move/resize/select operations "จัดหน้าจอ" mode needs. Every mutator returns a new instance;
 * [dev.autobridge.duoscreen.input.DuoScreenInputRouter] is the only thing expected to hold the current one.
 */
class DuoScreenPaneSet private constructor(private val panesInZOrder: List<DuoScreenPane>) {
    val panes: List<DuoScreenPane> get() = panesInZOrder

    fun pane(id: Int): DuoScreenPane? = panesInZOrder.firstOrNull { it.id == id }

    /** Topmost pane containing (x, y), or null if none does (e.g. a gap left after a resize). */
    fun paneAt(x: Int, y: Int): DuoScreenPane? = panesInZOrder.lastOrNull { it.rect.contains(x, y) }

    /** Every seam two panes currently share, left-to-right then top-to-bottom. */
    fun dividers(tolerance: Int = DuoScreenLayout.SEAM_TOLERANCE_PX): List<Divider> {
        val found = mutableListOf<Divider>()
        for (i in panesInZOrder.indices) {
            for (j in i + 1 until panesInZOrder.size) {
                val a = panesInZOrder[i]
                val b = panesInZOrder[j]
                Axis.entries.forEach { axis ->
                    DuoScreenLayout.dividerBetween(a.id, a.rect, b.id, b.rect, axis, tolerance)?.let(found::add)
                }
            }
        }
        return found
    }

    /**
     * The seam nearest to (x, y) within [grabPx] of it, or null — which is what makes a tap in the
     * middle of a pane select that pane while a tap on the join between two grabs the join. An
     * arrangement with no shared seams at all (picture-in-picture) never returns one.
     */
    fun dividerAt(
        x: Int,
        y: Int,
        grabPx: Int = DuoScreenLayout.DIVIDER_GRAB_PX,
        tolerance: Int = DuoScreenLayout.SEAM_TOLERANCE_PX
    ): Divider? =
        dividers(tolerance)
            .mapNotNull { divider ->
                DuoScreenLayout.distanceToDivider(divider, x, y)
                    ?.takeIf { it <= grabPx }
                    ?.let { divider to it }
            }
            .minByOrNull { (_, distance) -> distance }
            ?.first

    fun bringToFront(id: Int): DuoScreenPaneSet {
        val index = panesInZOrder.indexOfFirst { it.id == id }
        if (index < 0 || index == panesInZOrder.lastIndex) return this
        val reordered = panesInZOrder.toMutableList()
        val pane = reordered.removeAt(index)
        reordered.add(pane)
        return DuoScreenPaneSet(reordered)
    }

    fun withRect(id: Int, newRect: Rect): DuoScreenPaneSet {
        if (pane(id) == null) return this
        return DuoScreenPaneSet(panesInZOrder.map { if (it.id == id) it.copy(rect = newRect) else it })
    }

    /** Points a pane at another app. The rect, and the pane's place in the z-order, are kept. */
    fun withPackage(id: Int, packageName: String?): DuoScreenPaneSet {
        if (pane(id) == null) return this
        return DuoScreenPaneSet(
            panesInZOrder.map { if (it.id == id) it.copy(packageName = packageName) else it }
        )
    }

    /**
     * Pane ids whose app differs from [packages], which is indexed by pane id — the panes a
     * settings change has to reopen, and only those. A pane id past the end of [packages] counts
     * as changed only if it currently has an app, so a shorter list empties rather than keeps.
     */
    fun panesWithOtherPackage(packages: List<String?>): List<Int> =
        panesInZOrder
            .filter { pane -> pane.packageName != packages.getOrNull(pane.id) }
            .map { it.id }
            .sorted()

    companion object {
        /** Restores an arrangement as-is, in the order given (which is also its z-order). */
        fun of(panes: List<DuoScreenPane>): DuoScreenPaneSet = DuoScreenPaneSet(panes.toList())

        fun evenColumns(bounds: Bounds, packageNames: List<String?>): DuoScreenPaneSet {
            val rects = DuoScreenPaneLayouts.evenColumns(packageNames.size, bounds)
            val panes = rects.mapIndexed { index, rect -> DuoScreenPane(index, packageNames[index], rect) }
            return DuoScreenPaneSet(panes)
        }
    }
}
