package dev.autobridge.duoscreen.layout

import dev.autobridge.duoscreen.layout.DuoScreenLayout.Bounds
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DuoScreenPaneSetTest {
    private val bounds = Bounds(900, 600)

    @Test fun evenColumnsTilesTheBoundsExactlyForTwoPanes() {
        val columns = DuoScreenPaneLayouts.evenColumns(2, bounds)
        assertEquals(listOf(Rect(0, 0, 450, 600), Rect(450, 0, 450, 600)), columns)
    }

    @Test fun evenColumnsAbsorbsTheRoundingRemainderInTheLastColumn() {
        // 1000 / 3 = 333 remainder 1: the first two columns are 333px, the last absorbs the extra px.
        val columns = DuoScreenPaneLayouts.evenColumns(3, Bounds(1000, 600))
        assertEquals(listOf(333, 333, 334), columns.map { it.width })
        assertEquals(columns[0].right, columns[1].left) // contiguous, no gap
        assertEquals(columns[1].right, columns[2].left)
        assertEquals(1000, columns.last().right)
    }

    @Test fun paneAtReturnsTheTopmostMatch() {
        val set = DuoScreenPaneSet.evenColumns(bounds, listOf("a", "b"))
        val overlapping = set.withRect(0, Rect(0, 0, 500, 600)) // pane 0 now overlaps pane 1's area
        assertEquals(1, overlapping.paneAt(460, 100)?.id) // pane 1 is still on top (later in z-order)
    }

    @Test fun paneAtReturnsNullOutsideEveryPane() {
        val set = DuoScreenPaneSet.evenColumns(bounds, listOf("a", "b"))
        assertNull(set.paneAt(-10, 0))
    }

    @Test fun bringToFrontChangesHitTestOrder() {
        val set = DuoScreenPaneSet.evenColumns(bounds, listOf("a", "b"))
            .withRect(0, Rect(0, 0, 900, 600)) // pane 0 now covers everything, including pane 1's area
        assertEquals(1, set.paneAt(460, 10)?.id) // pane 1 still wins where both overlap: it is later in z-order
        val reordered = set.bringToFront(0)
        assertEquals(0, reordered.paneAt(460, 10)?.id) // now pane 0 wins
    }

    @Test fun withRectOnAnUnknownIdIsANoOp() {
        val set = DuoScreenPaneSet.evenColumns(bounds, listOf("a", "b"))
        assertEquals(set.panes, set.withRect(99, Rect(0, 0, 10, 10)).panes)
    }

    // --- swapping a pane's app ---

    @Test fun withPackageKeepsTheRectAndTheZOrder() {
        val set = DuoScreenPaneSet.evenColumns(bounds, listOf("a", "b")).bringToFront(0)
        val swapped = set.withPackage(0, "c")
        assertEquals("c", swapped.pane(0)?.packageName)
        assertEquals(set.pane(0)?.rect, swapped.pane(0)?.rect)
        assertEquals(0, swapped.panes.last().id)
    }

    @Test fun withPackageOnAnUnknownIdIsANoOp() {
        val set = DuoScreenPaneSet.evenColumns(bounds, listOf("a", "b"))
        assertEquals(set.panes, set.withPackage(99, "c").panes)
    }

    @Test fun onlyThePanesWhoseAppChangedAreReported() {
        val set = DuoScreenPaneSet.evenColumns(bounds, listOf("a", "b"))
        assertEquals(listOf(1), set.panesWithOtherPackage(listOf("a", "c")))
        assertEquals(emptyList<Int>(), set.panesWithOtherPackage(listOf("a", "b")))
    }

    @Test fun clearingAPanesAppCountsAsAChange() {
        val set = DuoScreenPaneSet.evenColumns(bounds, listOf("a", "b"))
        assertEquals(listOf(0), set.panesWithOtherPackage(listOf(null, "b")))
    }

    @Test fun aPaneMissingFromTheStoredListIsEmptiedNotKept() {
        val set = DuoScreenPaneSet.evenColumns(bounds, listOf("a", "b"))
        assertEquals(listOf(1), set.panesWithOtherPackage(listOf("a")))
    }

    @Test fun anEmptyPaneThatIsStillEmptyIsNotReopened() {
        val set = DuoScreenPaneSet.evenColumns(bounds, listOf("a", null))
        assertEquals(emptyList<Int>(), set.panesWithOtherPackage(listOf("a", null)))
    }

    @Test fun twoColumnsShareExactlyOneSeam() {
        val set = DuoScreenPaneSet.evenColumns(bounds, listOf("a", "b"))
        val seam = set.dividers().single()
        assertEquals(450, seam.position)
        assertEquals(DuoScreenLayout.Axis.VERTICAL, seam.axis)
    }

    @Test fun threeColumnsShareTwoSeams() {
        val set = DuoScreenPaneSet.evenColumns(bounds, listOf("a", "b", "c"))
        assertEquals(listOf(300, 600), set.dividers().map { it.position })
    }

    @Test fun dividerAtPrefersTheNearestSeam() {
        val set = DuoScreenPaneSet.evenColumns(bounds, listOf("a", "b", "c"))
        assertEquals(300, set.dividerAt(310, 300)?.position)
        assertEquals(600, set.dividerAt(590, 300)?.position)
    }

    @Test fun dividerAtIsNullInTheMiddleOfAPane() {
        val set = DuoScreenPaneSet.evenColumns(bounds, listOf("a", "b"))
        assertNull(set.dividerAt(225, 300))
    }

    @Test fun stackedPanesShareAHorizontalSeam() {
        val set = DuoScreenPaneSet.of(
            DuoScreenPreset.EVEN_ROWS.rects(2, bounds).mapIndexed { index, rect ->
                DuoScreenPane(index, null, rect)
            }
        )
        val seam = set.dividerAt(450, 305)!!
        assertEquals(DuoScreenLayout.Axis.HORIZONTAL, seam.axis)
        assertEquals(300, seam.position)
    }

    @Test fun floatingTilesShareNoSeamWithThePaneTheySitOn() {
        val set = DuoScreenPaneSet.of(
            DuoScreenPreset.PICTURE_IN_PICTURE.rects(2, bounds).mapIndexed { index, rect ->
                DuoScreenPane(index, null, rect)
            }
        )
        assertEquals(emptyList<DuoScreenLayout.Divider>(), set.dividers())
    }
}
