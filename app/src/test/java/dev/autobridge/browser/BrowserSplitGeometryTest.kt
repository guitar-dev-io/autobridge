package dev.autobridge.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSplitGeometryTest {
    private val gap = 4
    private val minPane = 180

    private fun split(
        layout: BrowserSplitLayout, w: Int, h: Int, sideOnRight: Boolean = false,
    ): SplitPanes? = BrowserSplitGeometry.panes(layout, 0, 0, w, h, sideOnRight, gap, minPane)

    @Test fun singleHasNoSidePane() {
        assertNull(split(BrowserSplitLayout.SINGLE, 1280, 720))
    }

    @Test fun halfSplitsTheUsableWidthEvenly() {
        val panes = split(BrowserSplitLayout.HALF, 1280, 720)!!
        assertEquals(PaneRect(0, 0, 638, 720), panes.side)
        assertEquals(PaneRect(642, 0, 1280, 720), panes.main)
    }

    @Test fun fortySixtyGivesTheSidePaneForty() {
        val panes = split(BrowserSplitLayout.FORTY_SIXTY, 1280, 720)!!
        assertEquals(510, panes.side.width) // 40% of 1276
        assertEquals(766, panes.main.width)
        assertEquals(gap, panes.main.left - panes.side.right)
    }

    @Test fun portraitLandscapeFillsAWideScreenWithExactSixteenByNine() {
        val panes = split(BrowserSplitLayout.PORTRAIT_LANDSCAPE, 1920, 720)!!
        assertEquals(PaneRect(1916 - 1280 + 4, 0, 1920, 720).width, panes.main.width)
        assertEquals(1280, panes.main.width)
        assertEquals(720, panes.main.height)
        assertTrue("side pane is portrait", panes.side.height > panes.side.width)
    }

    @Test fun portraitLandscapeLetterboxesTheMainPaneOnA720pScreen() {
        val panes = split(BrowserSplitLayout.PORTRAIT_LANDSCAPE, 1280, 720)!!
        // Capped at 70% of the usable width, then kept at 16:9 and centred vertically.
        assertEquals(893, panes.main.width)
        assertEquals(502, panes.main.height)
        assertEquals((720 - 502) / 2, panes.main.top)
        assertEquals(720, panes.side.height)
        assertTrue("side pane is portrait", panes.side.height > panes.side.width)
    }

    @Test fun sideOnRightMirrorsTheLayout() {
        val left = split(BrowserSplitLayout.FORTY_SIXTY, 1280, 720)!!
        val right = split(BrowserSplitLayout.FORTY_SIXTY, 1280, 720, sideOnRight = true)!!
        assertEquals(left.side.width, right.side.width)
        assertEquals(1280, right.side.right)
        assertEquals(0, right.main.left)
    }

    @Test fun aSurfaceTooNarrowForTwoUsablePanesStaysSingle() {
        assertNull(split(BrowserSplitLayout.FORTY_SIXTY, 400, 480))
    }

    /** Every layout on every head unit size: panes inside the card, never overlapping. */
    @Test fun panesStayInsideTheCardAndApart() {
        val sizes = listOf(800 to 480, 1024 to 600, 1280 to 720, 1920 to 720, 1920 to 1080)
        BrowserSplitLayout.entries.filter { it != BrowserSplitLayout.SINGLE }.forEach { layout ->
            sizes.forEach { (w, h) ->
                listOf(false, true).forEach { onRight ->
                    val panes = split(layout, w, h, onRight)
                    assertNotNull("$layout ${w}x$h", panes)
                    panes!!
                    listOf(panes.main, panes.side).forEach { p ->
                        assertTrue("$layout ${w}x$h $p inside", p.left >= 0 && p.top >= 0 && p.right <= w && p.bottom <= h)
                        assertTrue("$layout ${w}x$h $p non-empty", p.width >= minPane && p.height > 0)
                    }
                    val apart = panes.main.right <= panes.side.left || panes.side.right <= panes.main.left
                    assertTrue("$layout ${w}x$h overlap", apart)
                }
            }
        }
    }
}
