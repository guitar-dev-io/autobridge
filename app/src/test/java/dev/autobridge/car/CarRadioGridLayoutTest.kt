package dev.autobridge.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarRadioGridLayoutTest {
    private val area = CarRadioBox(0f, 0f, 1200f, 600f)
    private val labels = listOf(90f, 80f, 80f, 90f, 50f)

    private fun layout(cells: Int = 12, density: Float = 1f) = CarRadioGridLayout.compute(area, density, labels, cells)

    @Test fun threeColumnsOfEqualWidth() {
        val l = layout()
        val first = l.cellBox(0, 0f)
        val third = l.cellBox(2, 0f)
        val fourth = l.cellBox(3, 0f)
        assertEquals(first.width, third.width, 0.01f)
        assertEquals(l.gridViewport.right, third.right, 0.5f)
        assertEquals(first.left, fourth.left, 0.01f)
        assertTrue(fourth.top > first.bottom)
    }

    @Test fun tabsSitBetweenBackAndSearch() {
        val l = layout()
        assertEquals(5, l.tabs.size)
        assertTrue(l.tabs.first().left > l.back.right)
        assertTrue(l.tabs.last().right < l.search.left)
    }

    @Test fun tabsShrinkOnANarrowSurface() {
        val l = CarRadioGridLayout.compute(CarRadioBox(0f, 0f, 700f, 480f), 1f, labels.map { it * 2 }, 6)
        assertTrue(l.tabs.last().right <= l.search.left)
        assertTrue(l.tabs.all { it.width > 0f })
    }

    @Test fun scrollRangeCoversEveryRow() {
        val l = layout(cells = 30)
        assertTrue(l.maxScroll > 0f)
        val last = l.cellBox(29, l.maxScroll)
        assertEquals(l.gridViewport.bottom, last.bottom, 0.5f)
        assertEquals(0f, layout(cells = 3).maxScroll, 0f)
    }

    @Test fun hitsFollowTheScroll() {
        val l = layout(cells = 30)
        val cell = l.cellBox(4, 0f)
        assertEquals(CarRadioGridRegion.Cell(4), l.hit(cell.centerX, cell.centerY, 0f, false))
        val scrolled = l.cellBox(7, l.cellHeight + l.gap)
        assertEquals(CarRadioGridRegion.Cell(7), l.hit(scrolled.centerX, scrolled.centerY, l.cellHeight + l.gap, false))
        assertEquals(CarRadioGridRegion.Back, l.hit(l.back.centerX, l.back.centerY, 0f, false))
        assertEquals(CarRadioGridRegion.Search, l.hit(l.search.centerX, l.search.centerY, 0f, false))
        assertEquals(CarRadioGridRegion.Tab(2), l.hit(l.tabs[2].centerX, l.tabs[2].centerY, 0f, false))
    }

    @Test fun messageActionOnlyWhenShown() {
        val l = layout(cells = 0)
        val b = l.messageAction
        assertNull(l.hit(b.centerX, b.centerY, 0f, false))
        assertEquals(CarRadioGridRegion.MessageAction, l.hit(b.centerX, b.centerY, 0f, true))
    }
}
