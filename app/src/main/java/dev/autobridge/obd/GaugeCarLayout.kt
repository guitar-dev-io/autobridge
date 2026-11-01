package dev.autobridge.obd

import kotlin.math.ceil
import kotlin.math.min

/** One gauge's place on the car surface, in pixels. */
data class GaugeCell(
    val gauge: Gauge,
    val kind: CellKind,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

data class GaugeCarPage(val headerHeight: Float, val cells: List<GaugeCell>)

/**
 * Where a [GaugePage]'s gauges go on the car's drawing surface.
 *
 * Pure geometry, so the rules can be tested without a head unit: every cell stays inside the safe
 * area, no two overlap, and the split depends on the shape of the screen, because a tall tile has
 * no room for a name and a number side by side. [compute] takes the layout from the page, so any
 * page the user arranges on the phone gets the same guarantees.
 */
object GaugeCarLayout {

    fun compute(left: Float, top: Float, right: Float, bottom: Float, density: Float, page: GaugePage): GaugeCarPage {
        val gap = 10f * density
        val headerHeight = 38f * density
        val width = right - left
        val bodyTop = top + headerHeight + gap
        val bodyHeight = bottom - bodyTop
        val aspect = if (bodyHeight > 0f) width / bodyHeight else 1f
        val cells = ArrayList<GaugeCell>()
        val gauges = page.gauges
        fun take(from: Int, count: Int) = gauges.subList(from, from + count)
        fun grid(list: List<Gauge>, kind: CellKind, l: Float, t: Float, r: Float, b: Float, columns: Int) =
            place(cells, list, kind, l, t, r, b, columns, gap)

        when (page.layout) {
            PageLayout.DIAL1 -> grid(take(0, 1), CellKind.DIAL, left, bodyTop, right, bottom, 1)
            PageLayout.DIALS2 -> grid(take(0, 2), CellKind.DIAL, left, bodyTop, right, bottom, if (aspect >= 1.2f) 2 else 1)
            PageLayout.DIALS3 -> grid(take(0, 3), CellKind.DIAL, left, bodyTop, right, bottom, if (aspect >= 1.6f) 3 else 1)
            PageLayout.DIALS2_TILES3 -> {
                val dialHeight = if (aspect < 0.9f) min(bodyHeight * 0.5f, width / 2f * 1.05f) else bodyHeight * 0.64f
                grid(take(0, 2), CellKind.DIAL, left, bodyTop, right, bodyTop + dialHeight, 2)
                grid(take(2, 3), CellKind.TILE, left, bodyTop + dialHeight + gap, right, bottom, 3)
            }
            PageLayout.DIAL1_TILES4 -> if (aspect >= 1.4f) {
                val split = left + (right - left) * 0.4f
                grid(take(0, 1), CellKind.DIAL, left, bodyTop, split - gap / 2f, bottom, 1)
                grid(take(1, 4), CellKind.TILE, split + gap / 2f, bodyTop, right, bottom, 2)
            } else {
                val dialHeight = min(bodyHeight * 0.45f, width)
                grid(take(0, 1), CellKind.DIAL, left, bodyTop, right, bodyTop + dialHeight, 1)
                grid(take(1, 4), CellKind.TILE, left, bodyTop + dialHeight + gap, right, bottom, 2)
            }
            PageLayout.TILES4 -> grid(take(0, 4), CellKind.TILE, left, bodyTop, right, bottom, 2)
            PageLayout.TILES6 -> grid(take(0, 6), CellKind.TILE, left, bodyTop, right, bottom, if (aspect >= 1.8f) 3 else 2)
            // Four columns only on a screen wide enough that the tiles stay wider than tall; a
            // squarer one gets two columns of four rows.
            PageLayout.TILES8 -> grid(take(0, 8), CellKind.TILE, left, bodyTop, right, bottom, if (aspect >= 2f) 4 else 2)
        }
        return GaugeCarPage(headerHeight, cells)
    }

    private fun place(
        out: MutableList<GaugeCell>,
        gauges: List<Gauge>,
        kind: CellKind,
        left: Float, top: Float, right: Float, bottom: Float,
        columns: Int,
        gap: Float,
    ) {
        val rows = ceil(gauges.size / columns.toFloat()).toInt()
        val cellWidth = (right - left - gap * (columns - 1)) / columns
        val cellHeight = (bottom - top - gap * (rows - 1)) / rows
        gauges.forEachIndexed { index, gauge ->
            val column = index % columns
            val row = index / columns
            val x = left + column * (cellWidth + gap)
            val y = top + row * (cellHeight + gap)
            out += GaugeCell(gauge, kind, x, y, x + cellWidth, y + cellHeight)
        }
    }
}
