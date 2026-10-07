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
        sideFraction: Float? = null,
    ): SplitPanes? =
        BrowserSplitGeometry.panes(layout, 0, 0, w, h, sideOnRight, gap, minPane, sideFraction)

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

    @Test fun sixtyFiveThirtyFiveGivesTheSidePaneThirtyFive() {
        val panes = split(BrowserSplitLayout.SIXTY_FIVE_THIRTY_FIVE, 1280, 720)!!
        assertEquals(447, panes.side.width) // 35% of 1276 (usable = width - gap)
        assertEquals(829, panes.main.width) // the remaining 65%
        assertEquals(gap, panes.main.left - panes.side.right)
        assertEquals(720, panes.main.height)
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

    // --- a dragged divider ---

    @Test fun aDraggedFractionOverridesThePresetsOwnRatio() {
        val panes = split(BrowserSplitLayout.SIXTY_FIVE_THIRTY_FIVE, 1280, 720, sideFraction = 0.5f)!!
        assertEquals(638, panes.side.width)
        assertEquals(638, panes.main.width)
    }

    @Test fun aDraggedFractionAlsoOverridesTheSixteenByNinePreset() {
        val panes = split(BrowserSplitLayout.PORTRAIT_LANDSCAPE, 1280, 720, sideFraction = 0.25f)!!
        assertEquals(319, panes.side.width)
        assertEquals(720, panes.main.height) // no longer letterboxed to 16:9
    }

    @Test fun singleIgnoresADraggedFraction() {
        assertNull(split(BrowserSplitLayout.SINGLE, 1280, 720, sideFraction = 0.5f))
    }

    @Test fun aFractionPastTheEdgeIsHeldAtTheWidestAllowedSplit() {
        val panes = split(BrowserSplitLayout.HALF, 1280, 720, sideFraction = 0.99f)!!
        // On a 1280px panel the 80% cap binds before the 180px floor does: 20% of 1276 is 255px.
        assertEquals(255, panes.main.width)
        assertEquals(1276, panes.side.width + panes.main.width)
    }

    @Test fun clampingLeavesAWorkableFractionAlone() {
        assertEquals(0.4f, BrowserSplitGeometry.clampSideFraction(0.4f, 1276, minPane))
    }

    @Test fun clampingFallsBackToTheMidpointOnAPanelTooNarrowToSplit() {
        assertEquals(0.5f, BrowserSplitGeometry.clampSideFraction(0.2f, 300, minPane))
    }

    @Test fun draggingRightGrowsTheSidePaneWhenItIsOnTheLeft() {
        val panes = split(BrowserSplitLayout.HALF, 1280, 720)!!
        val fraction = BrowserSplitGeometry.dragSideFraction(panes, 100, sideOnRight = false, minPanePx = minPane)
        assertEquals((638 + 100) / 1276f, fraction)
    }

    @Test fun draggingRightShrinksTheSidePaneWhenItIsOnTheRight() {
        val panes = split(BrowserSplitLayout.HALF, 1280, 720, sideOnRight = true)!!
        val fraction = BrowserSplitGeometry.dragSideFraction(panes, 100, sideOnRight = true, minPanePx = minPane)
        assertEquals((638 - 100) / 1276f, fraction)
    }

    @Test fun aDragCannotPushAPaneUnderTheMinimum() {
        val panes = split(BrowserSplitLayout.HALF, 1280, 720)!!
        val fraction = BrowserSplitGeometry.dragSideFraction(panes, 10_000, sideOnRight = false, minPanePx = minPane)
        val dragged = split(BrowserSplitLayout.HALF, 1280, 720, sideFraction = fraction)!!
        assertTrue("main pane keeps the floor", dragged.main.width >= minPane)
        assertEquals(BrowserSplitGeometry.MAX_SIDE_FRACTION, fraction)
    }

    @Test fun aNarrowPanelStopsTheDragAtTheMinimumPaneWidthInstead() {
        // 800px panel: the 180px floor (22.6%) binds before the 20% cap does.
        val panes = split(BrowserSplitLayout.HALF, 800, 480)!!
        val fraction = BrowserSplitGeometry.dragSideFraction(panes, 10_000, sideOnRight = false, minPanePx = minPane)
        val dragged = split(BrowserSplitLayout.HALF, 800, 480, sideFraction = fraction)!!
        assertEquals(minPane, dragged.main.width)
    }

    @Test fun everyDraggedFractionStillLeavesTwoUsablePanes() {
        listOf(0.01f, 0.2f, 0.5f, 0.8f, 0.99f).forEach { fraction ->
            val panes = split(BrowserSplitLayout.HALF, 1280, 720, sideFraction = fraction)
            assertNotNull("fraction $fraction", panes)
            panes!!
            assertTrue("fraction $fraction side", panes.side.width >= minPane)
            assertTrue("fraction $fraction main", panes.main.width >= minPane)
        }
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

    @Test fun aPortraitPanelStacksThePanes() {
        val panes = split(BrowserSplitLayout.HALF, 1080, 1600)!!
        assertTrue(panes.stacked)
        assertEquals(PaneRect(0, 0, 1080, 798), panes.side)
        assertEquals(PaneRect(0, 802, 1080, 1600), panes.main)
    }

    @Test fun stackedSideOnRightPutsTheSidePageAtTheBottom() {
        val panes = split(BrowserSplitLayout.SIXTY_FIVE_THIRTY_FIVE, 1080, 1600, sideOnRight = true)!!
        assertEquals(1600, panes.side.bottom)
        assertEquals(0, panes.main.top)
        assertEquals(559, panes.side.height) // 35% of 1596, rounded
    }

    @Test fun stackedSixteenByNineKeepsTheMainPaneFullWidthSixteenByNine() {
        val panes = split(BrowserSplitLayout.PORTRAIT_LANDSCAPE, 1080, 1600)!!
        assertEquals(1080, panes.main.width)
        assertEquals(608, panes.main.height)
        assertEquals(1596 - 608, panes.side.height)
    }

    @Test fun stackedDividerDragsVertically() {
        val panes = split(BrowserSplitLayout.HALF, 1080, 1600)!!
        val grown = BrowserSplitGeometry.dragSideFraction(panes, 100, sideOnRight = false, minPanePx = minPane)
        assertEquals((798 + 100) / 1596f, grown, 0.001f)
    }
}
