package dev.autobridge.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GaugeCarLayoutTest {
    private fun overlap(a: GaugeCell, b: GaugeCell) =
        a.left < b.right - 0.01f && b.left < a.right - 0.01f && a.top < b.bottom - 0.01f && b.top < a.bottom - 0.01f

    private fun check(width: Float, height: Float, page: Int) {
        val layout = GaugeCarLayout.compute(10f, 20f, 10f + width, 20f + height, 1.5f, page)
        assertTrue(layout.cells.isNotEmpty())
        for (cell in layout.cells) {
            assertTrue("$cell left", cell.left >= 10f - 0.01f)
            assertTrue("$cell top", cell.top >= 20f + layout.headerHeight - 0.01f)
            assertTrue("$cell right", cell.right <= 10f + width + 0.01f)
            assertTrue("$cell bottom", cell.bottom <= 20f + height + 0.01f)
            assertTrue("$cell size", cell.width > 0 && cell.height > 0)
        }
        for (i in layout.cells.indices) for (j in i + 1 until layout.cells.size) {
            assertTrue("${layout.cells[i]} overlaps ${layout.cells[j]}", !overlap(layout.cells[i], layout.cells[j]))
        }
    }

    @Test fun everyPageFitsAWideAndATallScreen() {
        for (page in 0 until GaugeCarLayout.PAGES) {
            check(1200f, 600f, page)
            check(700f, 1100f, page)
            check(800f, 480f, page)
        }
    }

    @Test fun theDrivePageHasTwoDialsAndThreeTiles() {
        val cells = GaugeCarLayout.compute(0f, 0f, 1200f, 600f, 1f, 0).cells
        assertEquals(listOf(Gauge.RPM, Gauge.SPEED), cells.filter { it.kind == CellKind.DIAL }.map { it.gauge })
        assertEquals(GaugeCarLayout.DRIVE_TILES, cells.filter { it.kind == CellKind.TILE }.map { it.gauge })
    }

    @Test fun theEnginePageShowsEveryRemainingGauge() {
        val cells = GaugeCarLayout.compute(0f, 0f, 1200f, 600f, 1f, 1).cells
        assertEquals(GaugeCarLayout.ENGINE_TILES, cells.map { it.gauge })
    }

    @Test fun aTallScreenStacksTheEngineTilesInTwoColumns() {
        val cells = GaugeCarLayout.compute(0f, 0f, 700f, 1100f, 1f, 1).cells
        assertEquals(2, cells.map { it.left }.distinct().size)
        val wide = GaugeCarLayout.compute(0f, 0f, 1200f, 600f, 1f, 1).cells
        assertEquals(4, wide.map { it.left }.distinct().size)
    }

    @Test fun pagesWrapAround() {
        assertEquals(
            GaugeCarLayout.compute(0f, 0f, 900f, 500f, 1f, 0).cells,
            GaugeCarLayout.compute(0f, 0f, 900f, 500f, 1f, GaugeCarLayout.PAGES).cells
        )
    }
}
