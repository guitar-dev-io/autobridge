package dev.autobridge.obd

import kotlin.math.ceil
import kotlin.math.min

enum class CellKind { DIAL, TILE }

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
 * Where the gauges go on the car's drawing surface.
 *
 * Pure geometry, so the rules can be tested without a head unit: every cell stays inside the safe
 * area, no two overlap, and a tall (portrait) screen is split differently from a wide one. The
 * first page is the drive page, two dials over three tiles; the second is a grid of the rest.
 */
object GaugeCarLayout {
    const val PAGES = 2

    val DRIVE_DIALS = listOf(Gauge.RPM, Gauge.SPEED)
    val DRIVE_TILES = listOf(Gauge.COOLANT, Gauge.ATF, Gauge.BATTERY)
    val ENGINE_TILES = listOf(
        Gauge.BOOST, Gauge.LOAD, Gauge.INTAKE, Gauge.MAP,
        Gauge.THROTTLE, Gauge.FUEL_LEVEL, Gauge.AMBIENT, Gauge.BARO,
    )

    fun compute(left: Float, top: Float, right: Float, bottom: Float, density: Float, page: Int): GaugeCarPage {
        val gap = 10f * density
        val headerHeight = 30f * density
        val width = right - left
        val height = bottom - top
        val portrait = height > width * 1.1f
        val bodyTop = top + headerHeight + gap
        val bodyHeight = bottom - bodyTop
        val cells = ArrayList<GaugeCell>()

        if (page % PAGES == 0) {
            val dialHeight = if (portrait) min(bodyHeight * 0.5f, width / 2f * 1.05f) else bodyHeight * 0.64f
            grid(cells, DRIVE_DIALS, CellKind.DIAL, left, bodyTop, right, bodyTop + dialHeight, 2, gap)
            grid(cells, DRIVE_TILES, CellKind.TILE, left, bodyTop + dialHeight + gap, right, bottom, 3, gap)
        } else {
            grid(cells, ENGINE_TILES, CellKind.TILE, left, bodyTop, right, bottom, if (portrait) 2 else 4, gap)
        }
        return GaugeCarPage(headerHeight, cells)
    }

    private fun grid(
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
