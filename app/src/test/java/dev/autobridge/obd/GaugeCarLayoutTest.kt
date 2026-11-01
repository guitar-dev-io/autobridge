package dev.autobridge.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GaugeCarLayoutTest {
    private val screens = listOf(1200f to 600f, 700f to 1100f, 800f to 480f, 1050f to 770f, 480f to 800f)

    private fun overlap(a: GaugeCell, b: GaugeCell) =
        a.left < b.right - 0.01f && b.left < a.right - 0.01f && a.top < b.bottom - 0.01f && b.top < a.bottom - 0.01f

    private fun pageOf(layout: PageLayout) = GaugePages.fit(layout, Gauge.entries)

    @Test fun everyLayoutFitsEveryScreenShapeWithoutOverlap() {
        for (layout in PageLayout.entries) for ((width, height) in screens) {
            val page = GaugeCarLayout.compute(10f, 20f, 10f + width, 20f + height, 1.5f, pageOf(layout))
            assertEquals("$layout cells", layout.size, page.cells.size)
            for (cell in page.cells) {
                val where = "$layout on ${width}x$height: $cell"
                assertTrue("$where left", cell.left >= 10f - 0.01f)
                assertTrue("$where top", cell.top >= 20f + page.headerHeight - 0.01f)
                assertTrue("$where right", cell.right <= 10f + width + 0.01f)
                assertTrue("$where bottom", cell.bottom <= 20f + height + 0.01f)
                assertTrue("$where size", cell.width > 0 && cell.height > 0)
            }
            for (i in page.cells.indices) for (j in i + 1 until page.cells.size) {
                assertTrue("$layout overlaps on ${width}x$height", !overlap(page.cells[i], page.cells[j]))
            }
        }
    }

    @Test fun cellsKeepTheKindAndGaugeOfTheirSlot() {
        val page = GaugePages.DEFAULT[0]
        val cells = GaugeCarLayout.compute(0f, 0f, 1200f, 600f, 1f, page).cells
        assertEquals(page.gauges, cells.map { it.gauge })
        assertEquals(page.layout.slots, cells.map { it.kind })
    }

    @Test fun aSquarishScreenKeepsTheEightTilesWiderThanTall() {
        val cells = GaugeCarLayout.compute(0f, 0f, 1050f, 770f, 1.5f, pageOf(PageLayout.TILES8)).cells
        assertEquals(2, cells.map { it.left }.distinct().size)
        assertTrue(cells.all { it.width > it.height })
        val wide = GaugeCarLayout.compute(0f, 0f, 1200f, 600f, 1f, pageOf(PageLayout.TILES8)).cells
        assertEquals(4, wide.map { it.left }.distinct().size)
    }

    @Test fun theDialBesideTilesLayoutSwitchesToStackedOnATallScreen() {
        val wide = GaugeCarLayout.compute(0f, 0f, 1200f, 600f, 1f, pageOf(PageLayout.DIAL1_TILES4)).cells
        assertTrue(wide[0].right <= wide[1].left)
        val tall = GaugeCarLayout.compute(0f, 0f, 500f, 900f, 1f, pageOf(PageLayout.DIAL1_TILES4)).cells
        assertTrue(tall[0].bottom <= tall[1].top)
    }
}
