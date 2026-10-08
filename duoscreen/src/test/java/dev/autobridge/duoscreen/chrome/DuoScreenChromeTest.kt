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

    private fun chrome(panes: List<DuoScreenPane>, bounds: DuoScreenLayout.Bounds, editing: Boolean = false) =
        DuoScreenChrome.compute(panes, bounds, 1f, editing, labels, measure)

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

    @Test fun aSmallSurfaceGetsTheCompactBar() {
        // The DHU's small profile: 800 x 400 at 160dpi, where the design's 72dp bar was a tenth
        // of the width.
        val small = DuoScreenChrome.boundsFor(800, 400, 1f)
        assertEquals(DuoScreenChromeSpec.COMPACT.BAR.toInt(), small.barPx)
        val panes = panes(DuoScreenPreset.EVEN_COLUMNS, small, count = 3)
        val c = chrome(panes, small)
        assertEquals(DuoScreenChromeSpec.COMPACT, c.spec)
        assertTrue("the bar is under 7% of the width", c.bar.width * 100 / small.width < 7)
        assertEquals(4, c.buttons.size)
        c.buttons.forEach { (_, box) -> assertTrue(DuoScreenLayout.overlaps(box, c.bar)) }
    }

    @Test fun aLayoutSavedWithTheBiggerBarIsLaidOutAgainOnASmallSurface() {
        val small = DuoScreenChrome.boundsFor(800, 400, 1f)
        val savedAt72 = listOf(
            DuoScreenPane(0, "a", Rect(0, 0, 364, 400)),
            DuoScreenPane(1, "b", Rect(436, 0, 364, 400))
        )
        assertFalse(DuoScreenChrome.hasRoomForBar(savedAt72, small))
        assertTrue(DuoScreenChrome.hasRoomForBar(panes(DuoScreenPreset.EVEN_COLUMNS, small), small))
    }

    @Test fun aLargePortraitUnitKeepsTheDesignsSizes() {
        assertEquals(DuoScreenChromeSpec.REGULAR, chrome(panes(DuoScreenPreset.STACKED_60_40, portrait), portrait).spec)
        assertEquals(DuoScreenChromeSpec.REGULAR.BAR.toInt(), portrait.barPx)
    }

    @Test fun theBarHoldsFourButtonsAndTheGripInsideIt() {
        val c = chrome(panes(DuoScreenPreset.STACKED_60_40, portrait), portrait)
        assertEquals(
            listOf(ChromeTarget.Kind.LAYOUT, ChromeTarget.Kind.SWAP, ChromeTarget.Kind.RELOAD, ChromeTarget.Kind.ARRANGE),
            c.buttons.map { it.first }
        )
        c.buttons.forEach { (_, box) -> assertTrue("$box inside ${c.bar}", DuoScreenLayout.overlaps(box, c.bar)) }
        val grip = c.grip!!
        assertTrue(c.buttons[1].second.right < grip.left && grip.right < c.buttons[2].second.left)
    }

    @Test fun eachButtonAnswersForItsOwnSpotAndThePanesKeepTheirs() {
        val panes = panes(DuoScreenPreset.STACKED_60_40, portrait)
        val c = chrome(panes, portrait)
        c.buttons.forEach { (kind, box) ->
            val (x, y) = box.center()
            assertEquals(ChromeTarget.Control(kind), c.hit(x, y))
        }
        val (gx, gy) = c.grip!!.center()
        assertEquals(ChromeTarget.Control(ChromeTarget.Kind.GRIP), c.hit(gx, gy))
        val (px, py) = panes[0].rect.center()
        assertNull("a tap in a pane is the pane's", c.hit(px, py))
    }

    @Test fun aButtonTakesTheBarsFullThickness() {
        val c = chrome(panes(DuoScreenPreset.STACKED_60_40, portrait), portrait)
        val layout = c.buttons.first().second
        assertEquals(ChromeTarget.Control(ChromeTarget.Kind.LAYOUT), c.hit(layout.left + 4, c.bar.top + 1))
        assertEquals(ChromeTarget.Control(ChromeTarget.Kind.LAYOUT), c.hit(layout.left + 4, c.bar.bottom - 1))
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
