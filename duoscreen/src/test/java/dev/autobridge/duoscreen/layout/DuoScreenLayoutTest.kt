package dev.autobridge.duoscreen.layout

import dev.autobridge.duoscreen.layout.DuoScreenLayout.Axis
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Bounds
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DuoScreenLayoutTest {
    private val bounds = Bounds(1000, 800)

    @Test fun clampLeavesAPaneThatAlreadyFitsUnchanged() {
        val rect = Rect(100, 100, 400, 300)
        assertEquals(rect, DuoScreenLayout.clamp(rect, bounds))
    }

    @Test fun clampShrinksBelowMinimumUpToTheFloor() {
        // 20% of 1000 = 200, 25% of 800 = 200
        val tooSmall = Rect(0, 0, 50, 50)
        val clamped = DuoScreenLayout.clamp(tooSmall, bounds)
        assertEquals(200, clamped.width)
        assertEquals(200, clamped.height)
    }

    @Test fun clampPullsAnOffscreenPaneBackInside() {
        val offscreen = Rect(900, 700, 300, 300)
        val clamped = DuoScreenLayout.clamp(offscreen, bounds)
        assertTrue(clamped.right <= bounds.width)
        assertTrue(clamped.bottom <= bounds.height)
        assertEquals(300, clamped.width)
        assertEquals(300, clamped.height)
    }

    @Test fun moveTranslatesByTheDrag() {
        val rect = Rect(100, 100, 300, 300)
        val moved = DuoScreenLayout.move(rect, dx = 50, dy = -20, bounds)
        assertEquals(150, moved.left)
        assertEquals(80, moved.top)
    }

    @Test fun moveClampsAtTheFarEdge() {
        val rect = Rect(100, 100, 300, 300)
        val moved = DuoScreenLayout.move(rect, dx = 10_000, dy = 0, bounds)
        assertEquals(bounds.width - rect.width, moved.left)
    }

    @Test fun moveSnapsWithinThresholdOfAnEdge() {
        // left edge at 20px, under the 24px snap threshold.
        val rect = Rect(20, 100, 300, 300)
        val snapped = DuoScreenLayout.move(rect, dx = 0, dy = 0, bounds)
        assertEquals(0, snapped.left)
    }

    @Test fun moveDoesNotSnapBeyondTheThreshold() {
        val rect = Rect(30, 100, 300, 300)
        val notSnapped = DuoScreenLayout.move(rect, dx = 0, dy = 0, bounds)
        assertEquals(30, notSnapped.left)
    }

    @Test fun moveSnapsToTheFarEdgeWithinThreshold() {
        // right edge at bounds.width - 10 = 990, so the gap to the far edge is 10px.
        val rect = Rect(690, 100, 300, 300)
        val snapped = DuoScreenLayout.move(rect, dx = 0, dy = 0, bounds)
        assertEquals(bounds.width - rect.width, snapped.left)
    }

    @Test fun resizeGrowsAroundTheFocusPoint() {
        // A 200x200 pane at (100,100); focus at its center (200,200), scale 2x.
        val rect = Rect(100, 100, 200, 200)
        val resized = DuoScreenLayout.resize(rect, focusX = 200, focusY = 200, scaleFactor = 2f, bounds)
        assertEquals(400, resized.width)
        assertEquals(400, resized.height)
        // The center stays put: new left = focus - half of new width.
        assertEquals(0, resized.left)
        assertEquals(0, resized.top)
    }

    @Test fun resizeNeverShrinksBelowTheMinimum() {
        val rect = Rect(100, 100, 300, 300)
        val resized = DuoScreenLayout.resize(rect, focusX = 250, focusY = 250, scaleFactor = 0.01f, bounds)
        assertEquals(bounds.minPaneWidth, resized.width)
        assertEquals(bounds.minPaneHeight, resized.height)
    }

    @Test fun resizeIgnoresANonFiniteOrNonPositiveScale() {
        val rect = Rect(100, 100, 300, 300)
        assertEquals(rect, DuoScreenLayout.resize(rect, 200, 200, Float.NaN, bounds))
        assertEquals(rect, DuoScreenLayout.resize(rect, 200, 200, 0f, bounds))
        assertEquals(rect, DuoScreenLayout.resize(rect, 200, 200, -1f, bounds))
    }

    @Test fun resizeStaysWithinBoundsWhenGrowingPastTheEdge() {
        val rect = Rect(800, 600, 200, 200)
        val resized = DuoScreenLayout.resize(rect, focusX = 900, focusY = 700, scaleFactor = 3f, bounds)
        assertTrue(resized.right <= bounds.width)
        assertTrue(resized.bottom <= bounds.height)
    }

    @Test fun overlapsDetectsIntersectingRects() {
        val a = Rect(0, 0, 100, 100)
        val b = Rect(50, 50, 100, 100)
        assertTrue(DuoScreenLayout.overlaps(a, b))
    }

    @Test fun overlapsIsFalseForAdjacentRects() {
        val a = Rect(0, 0, 100, 100)
        val b = Rect(100, 0, 100, 100)
        assertFalse(DuoScreenLayout.overlaps(a, b))
    }

    // --- surface pixels to pane-display pixels ---

    @Test fun aTapIsUnchangedWhileTheDisplayMatchesThePane() {
        val rect = Rect(400, 0, 600, 800)
        assertEquals(120 to 40, DuoScreenLayout.scaleToDisplay(120, 40, rect, 600, 800))
    }

    @Test fun aTapIsScaledWhenTheDisplayIsSmallerThanTheQuadItIsStretchedInto() {
        // The pane was dragged wider to 600px while its display is still the 300px it was created
        // at: the image is stretched 2x, so a tap 120px into the pane is 60px into the display.
        val rect = Rect(0, 0, 600, 800)
        assertEquals(60 to 20, DuoScreenLayout.scaleToDisplay(120, 40, rect, 300, 400))
    }

    @Test fun aTapNeverLandsOutsideTheDisplay() {
        val rect = Rect(0, 0, 600, 800)
        val (x, y) = DuoScreenLayout.scaleToDisplay(10_000, 10_000, rect, 300, 400)
        assertEquals(299, x)
        assertEquals(399, y)
    }

    @Test fun aTapOnAPaneWithNoAreaIsNotDividedByZero() {
        assertEquals(0 to 0, DuoScreenLayout.scaleToDisplay(10, 10, Rect(0, 0, 0, 0), 300, 400))
    }

    @Test fun aScrolledDistanceIsScaledTheSameWayButKeepsItsSign() {
        val rect = Rect(0, 0, 600, 800)
        assertEquals(-60 to 20, DuoScreenLayout.scaleDeltaToDisplay(-120, 40, rect, 300, 400))
    }

    @Test fun aScrolledDistanceIsNotClampedToTheDisplay() {
        val rect = Rect(0, 0, 600, 800)
        val (dx, _) = DuoScreenLayout.scaleDeltaToDisplay(2_000, 0, rect, 300, 400)
        assertEquals(1_000, dx)
    }

    // --- seams ---

    private val leftPane = Rect(0, 0, 400, 800)
    private val rightPane = Rect(400, 0, 600, 800)

    @Test fun dividerBetweenTwoColumnsRunsDownTheSharedEdge() {
        val divider = DuoScreenLayout.dividerBetween(0, leftPane, 1, rightPane, Axis.VERTICAL)!!
        assertEquals(0, divider.first)
        assertEquals(1, divider.second)
        assertEquals(400, divider.position)
        assertEquals(0, divider.from)
        assertEquals(800, divider.to)
    }

    @Test fun dividerNamesTheLeftPaneFirstWhateverOrderItIsGiven() {
        val divider = DuoScreenLayout.dividerBetween(1, rightPane, 0, leftPane, Axis.VERTICAL)!!
        assertEquals(0, divider.first)
        assertEquals(1, divider.second)
    }

    @Test fun thereIsNoDividerBetweenPanesThatDoNotTouch() {
        val faraway = Rect(600, 0, 400, 800)
        assertNull(DuoScreenLayout.dividerBetween(0, leftPane, 1, faraway, Axis.VERTICAL))
    }

    @Test fun thereIsNoDividerBetweenPanesThatShareNoStretchOfTheEdge() {
        val below = Rect(400, 0, 600, 800)
        val stub = Rect(0, 800, 400, 0)
        assertNull(DuoScreenLayout.dividerBetween(0, stub, 1, below, Axis.VERTICAL))
    }

    @Test fun aSmallGapBetweenPanesIsStillOneSeam() {
        val nudged = Rect(405, 0, 595, 800)
        val divider = DuoScreenLayout.dividerBetween(0, leftPane, 1, nudged, Axis.VERTICAL)!!
        assertEquals(402, divider.position)
    }

    @Test fun aTapOffTheEndOfASeamMissesIt() {
        val divider = DuoScreenLayout.dividerBetween(0, Rect(0, 0, 400, 400), 1, Rect(400, 0, 600, 400), Axis.VERTICAL)!!
        assertEquals(0, DuoScreenLayout.distanceToDivider(divider, 400, 200))
        assertNull(DuoScreenLayout.distanceToDivider(divider, 400, 600))
    }

    @Test fun movingASeamGrowsOnePaneAndShrinksTheOther() {
        val (first, second) =
            DuoScreenLayout.moveDivider(leftPane, rightPane, 100, Axis.VERTICAL, bounds)
        assertEquals(Rect(0, 0, 500, 800), first)
        assertEquals(Rect(500, 0, 500, 800), second)
    }

    @Test fun movingASeamStopsAtTheMinimumPaneWidth() {
        val (first, second) =
            DuoScreenLayout.moveDivider(leftPane, rightPane, 10_000, Axis.VERTICAL, bounds)
        assertEquals(bounds.minPaneWidth, second.width)
        assertEquals(bounds.width, first.width + second.width)
    }

    @Test fun movingASeamLeavesTheGapBetweenThePanesUntouched() {
        val nudged = Rect(410, 0, 590, 800)
        val (first, second) =
            DuoScreenLayout.moveDivider(leftPane, nudged, -60, Axis.VERTICAL, bounds)
        assertEquals(10, second.left - first.right)
    }

    @Test fun aHorizontalSeamMovesOnTheVerticalAxis() {
        val top = Rect(0, 0, 1000, 300)
        val bottom = Rect(0, 300, 1000, 500)
        val (first, second) = DuoScreenLayout.moveDivider(top, bottom, 100, Axis.HORIZONTAL, bounds)
        assertEquals(Rect(0, 0, 1000, 400), first)
        assertEquals(Rect(0, 400, 1000, 400), second)
    }

    @Test fun aPaneAlreadyUnderTheMinimumIsNotSqueezedFurther() {
        // 150px is below the 200px floor, as a layout re-fitted onto a smaller panel can be.
        val pinched = Rect(0, 0, 150, 800)
        val rest = Rect(150, 0, 850, 800)
        assertEquals(
            pinched to rest,
            DuoScreenLayout.moveDivider(pinched, rest, -50, Axis.VERTICAL, bounds)
        )
    }

    @Test fun aPaneAlreadyUnderTheMinimumCanStillBeGrown() {
        val pinched = Rect(0, 0, 150, 800)
        val rest = Rect(150, 0, 850, 800)
        val (first, _) = DuoScreenLayout.moveDivider(pinched, rest, 50, Axis.VERTICAL, bounds)
        assertEquals(200, first.width)
    }

    @Test fun theBandIsCentredOnTheSeamItDraws() {
        val divider = DuoScreenLayout.dividerBetween(0, leftPane, 1, rightPane, Axis.VERTICAL)!!
        val band = DuoScreenLayout.band(divider)
        assertEquals(400, band.left + band.width / 2)
        assertEquals(800, band.height)
    }
}
