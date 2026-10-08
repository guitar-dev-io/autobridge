package dev.autobridge.duoscreen.chrome

import dev.autobridge.duoscreen.input.DuoScreenInputPort
import dev.autobridge.duoscreen.input.DuoScreenInputRouter
import dev.autobridge.duoscreen.layout.DuoScreenLayout
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Rect
import dev.autobridge.duoscreen.layout.DuoScreenPane
import dev.autobridge.duoscreen.layout.DuoScreenPaneSet
import dev.autobridge.duoscreen.layout.DuoScreenPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drawn chrome's geometry: where the bar goes for each kind of arrangement, that its buttons
 * answer for their own spot, and that a tap anywhere else still reaches the panes.
 */
class DuoScreenChromeTest {
    /** A portrait head unit's surface at density 1, with the bar and seam gaps. */
    private val portrait = DuoScreenChrome.boundsFor(1074, 1410, 1f)
    private val landscape = DuoScreenChrome.boundsFor(1184, 720, 1f)

    private val labels = ChromeLabels(
        swap = "Swap",
        done = "Done",
        changeApp = "Change app",
        chips = mapOf(
            DuoScreenPreset.EVEN_COLUMNS to "Left / right",
            DuoScreenPreset.STACKED_60_40 to "Top / bottom",
            DuoScreenPreset.PICTURE_IN_PICTURE to "Picture in picture"
        )
    )

    private val measure: (String, Float) -> Float = { text, size -> text.length * size * 0.55f }

    private fun panes(preset: DuoScreenPreset, bounds: DuoScreenLayout.Bounds, count: Int = 2) =
        preset.rects(count, bounds).mapIndexed { i, r -> DuoScreenPane(i, "app$i", r) }

    private fun chrome(
        panes: List<DuoScreenPane>,
        bounds: DuoScreenLayout.Bounds,
        editing: Boolean = false,
        toolbarOpen: Boolean = false
    ) = DuoScreenChrome.compute(panes, bounds, 1f, editing, labels, measure, toolbarOpen)

    private fun Rect.center() = (left + width / 2) to (top + height / 2)

    // --------------------------------------------------------------------- where the bar goes

    @Test fun aStackedLayoutGetsAHorizontalBarBetweenThePanes() {
        val panes = panes(DuoScreenPreset.STACKED_60_40, portrait)
        val c = chrome(panes, portrait)
        assertTrue(c.barHorizontal)
        assertEquals(panes[0].rect.bottom, c.bar.top)
        assertEquals(panes[1].rect.top, c.bar.bottom)
        assertFalse(c.barOverlapsPanes)
        assertNotNull(c.barDivider)
    }

    @Test fun sideBySidePanesGetAVerticalBar() {
        val panes = panes(DuoScreenPreset.EVEN_COLUMNS, landscape)
        val c = chrome(panes, landscape)
        assertFalse(c.barHorizontal)
        assertEquals(panes[0].rect.right, c.bar.left)
        assertEquals(panes[1].rect.left, c.bar.right)
    }

    @Test fun pictureInPictureGetsTheFreeStripAlongTheBottom() {
        val panes = panes(DuoScreenPreset.PICTURE_IN_PICTURE, portrait)
        val c = chrome(panes, portrait)
        assertTrue(c.barHorizontal)
        assertEquals(portrait.height, c.bar.bottom)
        assertFalse(c.barOverlapsPanes)
        assertNull("no seam to grab in picture-in-picture", c.barDivider)
    }

    @Test fun aSavedLayoutWithoutTheGapHasNoRoomForTheBar() {
        val edgeToEdge = listOf(
            DuoScreenPane(0, "a", Rect(0, 0, 1074, 800)),
            DuoScreenPane(1, "b", Rect(0, 800, 1074, 610))
        )
        assertFalse(DuoScreenChrome.hasRoomForBar(edgeToEdge, portrait))
        assertTrue(DuoScreenChrome.hasRoomForBar(panes(DuoScreenPreset.STACKED_60_40, portrait), portrait))
    }

    // ------------------------------------------------------------------------------ the bar

    @Test fun theSeamIsThinAndShowsOnlyItsHandleUntilOpened() {
        val panes = panes(DuoScreenPreset.STACKED_60_40, portrait)
        val c = chrome(panes, portrait)
        assertEquals(DuoScreenChromeSpec.REGULAR.BAR.toInt(), c.bar.height)
        assertTrue(c.buttons.isEmpty())
        assertNull(c.toolbar)
        val handle = c.handle!!
        assertTrue(kotlin.math.abs((c.bar.left + c.bar.width / 2) - (handle.left + handle.width / 2)) <= 1)
        val (hx, hy) = handle.center()
        assertEquals(ChromeTarget.Control(ChromeTarget.Kind.GRIP), c.hit(hx, hy))
    }

    @Test fun theHandleTakesAFingerSizedSquareThoughItIsThin() {
        val c = chrome(panes(DuoScreenPreset.STACKED_60_40, portrait), portrait)
        val (hx, hy) = c.handle!!.center()
        val reach = DuoScreenChromeSpec.REGULAR.HANDLE_TOUCH.toInt() / 2 - 2
        assertEquals(ChromeTarget.Control(ChromeTarget.Kind.GRIP), c.hit(hx, hy - reach))
        assertEquals(ChromeTarget.Control(ChromeTarget.Kind.GRIP), c.hit(hx, hy + reach))
    }

    @Test fun anOpenToolbarHoldsTheFourButtonsOverTheSeam() {
        val panes = panes(DuoScreenPreset.STACKED_60_40, portrait)
        val c = chrome(panes, portrait, toolbarOpen = true)
        val toolbar = c.toolbar!!
        assertEquals(
            listOf(ChromeTarget.Kind.LAYOUT, ChromeTarget.Kind.SWAP, ChromeTarget.Kind.RELOAD, ChromeTarget.Kind.ARRANGE),
            c.buttons.map { it.first }
        )
        assertTrue("the toolbar is centred on the seam", toolbar.top < c.bar.top && toolbar.bottom > c.bar.bottom)
        c.buttons.forEach { (kind, box) ->
            assertTrue(toolbar.left <= box.left && box.right <= toolbar.right)
            val (x, y) = box.center()
            assertEquals(ChromeTarget.Control(kind), c.hit(x, y))
        }
        val (px, py) = panes[0].rect.center()
        assertNull("a tap in a pane is not the toolbar's", c.hit(px, py))
        assertTrue("the toolbar between its buttons belongs to no pane", c.onBar(toolbar.left + 1, toolbar.top + toolbar.height / 2))
    }

    @Test fun aSideBySideToolbarRunsDownTheSeam() {
        val c = chrome(panes(DuoScreenPreset.EVEN_COLUMNS, landscape), landscape, toolbarOpen = true)
        val toolbar = c.toolbar!!
        assertTrue(toolbar.height > toolbar.width)
        assertTrue(c.buttons.zipWithNext().all { (a, b) -> a.second.bottom <= b.second.top })
    }

    @Test fun aToolbarNearTheEdgeStaysOnTheSurface() {
        val bottomBar = chrome(panes(DuoScreenPreset.PICTURE_IN_PICTURE, portrait), portrait, toolbarOpen = true)
        assertTrue(bottomBar.toolbar!!.bottom <= portrait.height)
    }

    @Test fun aSmallSurfaceGetsTheCompactSeam() {
        // The DHU's small profile: 800 x 400 at 160dpi, where even a 40dp bar of buttons read as big.
        val small = DuoScreenChrome.boundsFor(800, 400, 1f)
        assertEquals(DuoScreenChromeSpec.COMPACT.BAR.toInt(), small.barPx)
        val c = chrome(panes(DuoScreenPreset.EVEN_COLUMNS, small, count = 3), small, toolbarOpen = true)
        assertEquals(DuoScreenChromeSpec.COMPACT, c.spec)
        assertTrue("the seam is under 2% of the width", c.bar.width * 100 / small.width < 2)
        assertEquals(4, c.buttons.size)
    }

    @Test fun aLayoutSavedWithTheBiggerBarIsLaidOutAgainOnASmallSurface() {
        val small = DuoScreenChrome.boundsFor(800, 400, 1f)
        val savedAt40 = listOf(
            DuoScreenPane(0, "a", Rect(0, 0, 380, 400)),
            DuoScreenPane(1, "b", Rect(420, 0, 380, 400))
        )
        assertFalse(DuoScreenChrome.hasRoomForBar(savedAt40, small))
        assertTrue(DuoScreenChrome.hasRoomForBar(panes(DuoScreenPreset.EVEN_COLUMNS, small), small))
    }

    @Test fun aLargePortraitUnitGetsTheRegularSizes() {
        assertEquals(DuoScreenChromeSpec.REGULAR, chrome(panes(DuoScreenPreset.STACKED_60_40, portrait), portrait).spec)
        assertEquals(DuoScreenChromeSpec.REGULAR.BAR.toInt(), portrait.barPx)
    }

    // --------------------------------------------------------------------------- arranging

    @Test fun arrangingShowsTheChipsACardPerPaneAndSwapAndDone() {
        val panes = panes(DuoScreenPreset.STACKED_60_40, portrait)
        val c = chrome(panes, portrait, editing = true)
        assertEquals(DuoScreenChrome.CHIP_PRESETS, c.chips.map { it.first })
        assertEquals(panes.map { it.id }, c.cards.map { it.paneId })
        assertTrue(c.buttons.isEmpty())
        assertEquals(ChromeTarget.Control(ChromeTarget.Kind.DONE), c.donePill!!.center().let { (x, y) -> c.hit(x, y) })
        assertEquals(ChromeTarget.Control(ChromeTarget.Kind.SWAP), c.swapPill!!.center().let { (x, y) -> c.hit(x, y) })
        c.cards.forEach { card ->
            val (x, y) = card.button.center()
            assertEquals(ChromeTarget.ChangeApp(card.paneId), c.hit(x, y))
            assertTrue(DuoScreenLayout.overlaps(card.button, card.pane))
        }
    }

    @Test fun theCardsStateEachPanesShareOfTheHeight() {
        val c = chrome(panes(DuoScreenPreset.STACKED_60_40, portrait), portrait, editing = true)
        assertEquals(listOf(56, 44), c.cards.map { it.sharePercent })
        assertTrue(c.cards.all { it.shareAxis == DuoScreenChrome.ShareAxis.HEIGHT })
    }

    @Test fun chipsFallBackToIconsWhenTheirLabelsDoNotFit() {
        val narrow = DuoScreenChrome.boundsFor(560, 900, 1f)
        val c = chrome(panes(DuoScreenPreset.STACKED_60_40, narrow), narrow, editing = true)
        assertTrue(c.chipsIconOnly)
        assertTrue(c.chips.last().second.right <= narrow.width - c.spec.EXIT_RESERVE.toInt())
    }

    // --------------------------------------------------------------- the grip moves the seam

    @Test fun theGripGrabsTheSeamInNormalUseAndADragKeepsTheBarsGap() {
        val panes = panes(DuoScreenPreset.STACKED_60_40, portrait)
        val port = object : DuoScreenInputPort {
            var lastRects = mutableMapOf<Int, Rect>()
            override fun forwardTap(paneId: Int, localX: Int, localY: Int) = Unit
            override fun forwardScroll(paneId: Int, dx: Int, dy: Int) = Unit
            override fun onSelectionChanged(paneId: Int?) = Unit
            override fun onPaneRectChanged(paneId: Int, rect: Rect) { lastRects[paneId] = rect }
            override fun onDividerGrabbed(divider: DuoScreenLayout.Divider?) = Unit
        }
        val router = DuoScreenInputRouter(DuoScreenPaneSet.of(panes), portrait, port)
        val seam = chrome(panes, portrait).barDivider!!
        router.grabDivider(seam)
        // Finger dragged down 60px: the host reports a scroll distance of -60.
        router.onScroll(0, -60)
        val top = router.panes.pane(0)!!.rect
        val bottom = router.panes.pane(1)!!.rect
        assertEquals(panes[0].rect.height + 60, top.height)
        assertEquals(portrait.barPx, bottom.top - top.bottom)
        assertEquals(DuoScreenInputRouter.Mode.NORMAL, router.mode)
    }
}
