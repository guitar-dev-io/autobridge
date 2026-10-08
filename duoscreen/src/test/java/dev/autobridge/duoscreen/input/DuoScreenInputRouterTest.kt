package dev.autobridge.duoscreen.input

import dev.autobridge.duoscreen.layout.DuoScreenLayout
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Bounds
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Rect
import dev.autobridge.duoscreen.layout.DuoScreenPaneSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

private class RecordingPort : DuoScreenInputPort {
    val taps = mutableListOf<Triple<Int, Int, Int>>()
    val scrolls = mutableListOf<Triple<Int, Int, Int>>()
    val selections = mutableListOf<Int?>()
    val rectChanges = mutableListOf<Pair<Int, Rect>>()
    val grabs = mutableListOf<DuoScreenLayout.Divider?>()

    override fun onDividerGrabbed(divider: DuoScreenLayout.Divider?) {
        grabs += divider
    }

    override fun forwardTap(paneId: Int, localX: Int, localY: Int) {
        taps += Triple(paneId, localX, localY)
    }

    override fun forwardScroll(paneId: Int, dx: Int, dy: Int) {
        scrolls += Triple(paneId, dx, dy)
    }

    override fun onSelectionChanged(paneId: Int?) {
        selections += paneId
    }

    override fun onPaneRectChanged(paneId: Int, rect: Rect) {
        rectChanges += paneId to rect
    }
}

class DuoScreenInputRouterTest {
    private val bounds = Bounds(900, 600)
    private lateinit var port: RecordingPort
    private lateinit var router: DuoScreenInputRouter

    @Before fun setUp() {
        port = RecordingPort()
        router = DuoScreenInputRouter(DuoScreenPaneSet.evenColumns(bounds, listOf("a", "b")), bounds, port)
    }

    @Test fun normalModeClickForwardsAPaneLocalTap() {
        router.onClick(500, 10) // inside pane 1 (left=450)
        assertEquals(Triple(1, 50, 10), port.taps.single())
    }

    @Test fun normalModeScrollRoutesToTheLastTouchedPane() {
        router.onClick(10, 10) // touches pane 0
        router.onScroll(5, -5)
        assertEquals(Triple(0, 5, -5), port.scrolls.single())
    }

    @Test fun normalModeScrollBeforeAnyTapDoesNothing() {
        router.onScroll(5, 5)
        assertEquals(0, port.scrolls.size)
    }

    @Test fun normalModeClickNeverChangesSelection() {
        router.onClick(10, 10)
        assertEquals(0, port.selections.size)
    }

    @Test fun editModeClickSelectsAndBringsToFront() {
        router.setMode(DuoScreenInputRouter.Mode.EDIT)
        // Well clear of the seam at 450: within DIVIDER_GRAB_PX of it a tap grabs the seam instead.
        router.onClick(700, 10) // pane 1
        assertEquals(1, port.selections.single())
        assertEquals(1, router.panes.panes.last().id) // brought to front
    }

    @Test fun editModeClickNeverForwardsATap() {
        router.setMode(DuoScreenInputRouter.Mode.EDIT)
        router.onClick(10, 10)
        assertEquals(0, port.taps.size)
    }

    @Test fun editModeDragMovesTheSelectedPaneTheWayTheFingerWent() {
        router.setMode(DuoScreenInputRouter.Mode.EDIT)
        router.onClick(10, 10) // selects pane 0, rect (0,0,450,600)
        // A finger dragged right reports a negative scroll distance, and the pane follows it right.
        // Vertical movement is 0 here on purpose: the pane's height already equals bounds.height
        // (evenColumns gives full-height columns), so clamp() leaves no room to move vertically.
        router.onScroll(-30, -100)
        val (id, rect) = port.rectChanges.single()
        assertEquals(0, id)
        assertEquals(Rect(30, 0, 450, 600), rect)
    }

    @Test fun editModeDragWithNoSelectionDoesNothing() {
        router.setMode(DuoScreenInputRouter.Mode.EDIT)
        router.onScroll(-30, -10)
        assertEquals(0, port.rectChanges.size)
    }

    @Test fun editModePinchResizesAroundTheFocusPoint() {
        router.setMode(DuoScreenInputRouter.Mode.EDIT)
        router.onClick(10, 10) // selects pane 0, rect (0,0,450,600)
        router.onScale(0, 0, 2f)
        val (id, rect) = port.rectChanges.single()
        assertEquals(0, id)
        assertEquals(900, rect.width) // clamped to bounds.width
    }

    @Test fun switchingBackToNormalClearsSelection() {
        router.setMode(DuoScreenInputRouter.Mode.EDIT)
        router.onClick(10, 10)
        port.selections.clear()
        router.setMode(DuoScreenInputRouter.Mode.NORMAL)
        assertNull(port.selections.single())
    }

    @Test fun flingInNormalModeIsDampedBeforeForwarding() {
        router.onClick(10, 10) // touches pane 0
        router.onFling(80, -160)
        assertEquals(Triple(0, 10, -20), port.scrolls.single())
    }

    // --- dragging the seam between two panes ---

    @Test fun editModeClickOnTheSeamGrabsItInsteadOfSelectingAPane() {
        router.setMode(DuoScreenInputRouter.Mode.EDIT)
        router.onClick(455, 300) // the seam sits at x=450; 5px is well inside the grab margin
        val grabbed = port.grabs.single()!!
        assertEquals(0, grabbed.first)
        assertEquals(1, grabbed.second)
        assertEquals(DuoScreenLayout.Axis.VERTICAL, grabbed.axis)
        assertNull(port.selections.single())
    }

    @Test fun normalModeClickOnTheSeamStillForwardsATap() {
        router.onClick(455, 300)
        assertEquals(0, port.grabs.size)
        assertEquals(1, port.taps.size)
    }

    @Test fun editModeDragOnAGrabbedSeamResizesBothNeighbours() {
        router.setMode(DuoScreenInputRouter.Mode.EDIT)
        router.onClick(455, 300)
        router.onScroll(-50, 0) // finger dragged right
        assertEquals(2, port.rectChanges.size)
        assertEquals(Rect(0, 0, 500, 600), port.rectChanges[0].second)
        assertEquals(Rect(500, 0, 400, 600), port.rectChanges[1].second)
    }

    @Test fun draggingASeamNeverShrinksAPaneBelowTheMinimum() {
        router.setMode(DuoScreenInputRouter.Mode.EDIT)
        router.onClick(455, 300)
        router.onScroll(-10_000, 0) // all the way right
        // 20% of 900 = 180px is the floor for the pane being squeezed.
        assertEquals(Rect(720, 0, 180, 600), port.rectChanges.last().second)
    }

    @Test fun aSecondDragFollowsTheSeamToItsNewPosition() {
        router.setMode(DuoScreenInputRouter.Mode.EDIT)
        router.onClick(455, 300)
        router.onScroll(-50, 0)
        router.onScroll(-50, 0)
        assertEquals(Rect(0, 0, 550, 600), port.rectChanges[2].second)
        assertEquals(550, port.grabs.last()!!.position)
    }

    @Test fun aDragAlongAVerticalSeamMovesNothing() {
        router.setMode(DuoScreenInputRouter.Mode.EDIT)
        router.onClick(455, 300)
        router.onScroll(0, -120)
        assertEquals(0, port.rectChanges.size)
    }

    @Test fun tappingAPaneAfterwardsLetsTheSeamGo() {
        router.setMode(DuoScreenInputRouter.Mode.EDIT)
        router.onClick(455, 300)
        router.onClick(100, 300) // well clear of the seam
        assertNull(port.grabs.last())
        assertEquals(0, router.panes.panes.last().id)
    }

    @Test fun leavingEditModeLetsTheSeamGo() {
        router.setMode(DuoScreenInputRouter.Mode.EDIT)
        router.onClick(455, 300)
        router.setMode(DuoScreenInputRouter.Mode.NORMAL)
        assertNull(port.grabs.last())
        assertNull(router.divider)
    }

    @Test fun setRectAppliesAPresetRectAndReleasesTheSeam() {
        router.setMode(DuoScreenInputRouter.Mode.EDIT)
        router.onClick(455, 300)
        router.setRect(0, Rect(0, 0, 585, 600))
        assertNull(port.grabs.last())
        assertEquals(0 to Rect(0, 0, 585, 600), port.rectChanges.single())
    }
}
