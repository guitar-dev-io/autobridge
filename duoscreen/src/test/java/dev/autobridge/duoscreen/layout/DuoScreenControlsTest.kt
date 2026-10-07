package dev.autobridge.duoscreen.layout

import dev.autobridge.duoscreen.layout.DuoScreenLayout.Bounds
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DuoScreenControlsTest {
    private val portrait = Bounds(1074, 1410)
    private val landscape = Bounds(1280, 720)

    private fun stacked(bounds: Bounds, topShare: Float = 0.56f): List<DuoScreenPane> {
        val top = (bounds.height * topShare).toInt()
        return listOf(
            DuoScreenPane(0, "a", Rect(0, 0, bounds.width, top)),
            DuoScreenPane(1, "b", Rect(0, top, bounds.width, bounds.height - top))
        )
    }

    private fun columns(bounds: Bounds): List<DuoScreenPane> {
        val left = (bounds.width * 0.65f).toInt()
        return listOf(
            DuoScreenPane(0, "a", Rect(0, 0, left, bounds.height)),
            DuoScreenPane(1, "b", Rect(left, 0, bounds.width - left, bounds.height))
        )
    }

    private fun pip(bounds: Bounds): List<DuoScreenPane> = listOf(
        DuoScreenPane(0, "a", Rect(0, 0, bounds.width, bounds.height)),
        DuoScreenPane(1, "b", Rect(bounds.width - 360, bounds.height - 260, 340, 240))
    )

    private fun layout(
        panes: List<DuoScreenPane>,
        bounds: Bounds,
        editing: Boolean = false,
        menuOpen: Boolean = false,
        corner: DuoScreenFabCorner = DuoScreenFabCorner.BOTTOM_RIGHT
    ) = DuoScreenControlsGeometry.layout(panes, bounds, editing, menuOpen, corner, scale = 1f)

    @Test
    fun stackedPanesGetASeamBarCentredOnTheSeam() {
        val panes = stacked(portrait)
        val seamY = panes[0].rect.bottom
        val result = layout(panes, portrait)

        assertEquals(DuoScreenControlsLayout.Kind.SEAM_BAR, result.kind)
        assertNull("the bar sits in the gutter, with no backing panel", result.panel)
        result.buttons.forEach { button ->
            val centreY = button.rect.top + button.rect.height / 2
            assertTrue("${button.control} centred on the seam", kotlin.math.abs(centreY - seamY) <= 1)
        }
        assertEquals(
            listOf(
                DuoScreenControl.LAYOUT, DuoScreenControl.SWAP, DuoScreenControl.HANDLE,
                DuoScreenControl.PHONE_SCREEN, DuoScreenControl.RELOAD, DuoScreenControl.ARRANGE
            ),
            result.buttons.map { it.control }
        )
    }

    @Test
    fun seamBarPutsButtonsAtBothEndsAndTheHandleInTheMiddle() {
        val result = layout(stacked(portrait), portrait)
        val byControl = result.buttons.associateBy { it.control }
        // 16 px in from each end, 44 px buttons 8 px apart.
        assertEquals(16, byControl.getValue(DuoScreenControl.LAYOUT).rect.left)
        assertEquals(16 + 44 + 8, byControl.getValue(DuoScreenControl.SWAP).rect.left)
        assertEquals(portrait.width - 16, byControl.getValue(DuoScreenControl.ARRANGE).rect.right)
        assertEquals(portrait.width - 16 - 44 - 8, byControl.getValue(DuoScreenControl.RELOAD).rect.right)
        assertEquals(portrait.width - 16 - 2 * (44 + 8), byControl.getValue(DuoScreenControl.PHONE_SCREEN).rect.right)
        val handle = byControl.getValue(DuoScreenControl.HANDLE).rect
        assertEquals(portrait.width / 2, handle.left + handle.width / 2)
        assertEquals(44, byControl.getValue(DuoScreenControl.LAYOUT).rect.width)
    }

    @Test
    fun sideBySidePanesGetAVerticalBar() {
        val panes = columns(landscape)
        val seamX = panes[0].rect.right
        val result = layout(panes, landscape)

        val layoutButton = result.buttons.first { it.control == DuoScreenControl.LAYOUT }.rect
        val arrange = result.buttons.first { it.control == DuoScreenControl.ARRANGE }.rect
        assertTrue("buttons run down the seam", arrange.top > layoutButton.bottom)
        result.buttons.forEach { button ->
            val centreX = button.rect.left + button.rect.width / 2
            assertTrue(kotlin.math.abs(centreX - seamX) <= 1)
        }
    }

    @Test
    fun editingSwapsReloadAndArrangeForDone() {
        val result = layout(stacked(portrait), portrait, editing = true)
        val controls = result.buttons.map { it.control }
        assertTrue(DuoScreenControl.DONE in controls)
        assertTrue(DuoScreenControl.ARRANGE !in controls)
        assertTrue(DuoScreenControl.RELOAD !in controls)
        assertTrue(DuoScreenControl.PHONE_SCREEN !in controls)
    }

    @Test
    fun everyHitAreaIsAtLeastTheMinimumAndInsideTheSurface() {
        listOf(
            layout(stacked(portrait), portrait),
            layout(columns(landscape), landscape),
            layout(pip(landscape), landscape, menuOpen = true)
        ).forEach { result ->
            result.buttons.forEach { button ->
                assertTrue("${button.control} hit width", button.hit.width >= 64)
                assertTrue("${button.control} hit height", button.hit.height >= 64)
                assertTrue(button.hit.left >= 0 && button.hit.top >= 0)
            }
        }
    }

    @Test
    fun controlAtFindsAButtonAndMissesThePaneMiddle() {
        val result = layout(stacked(portrait), portrait)
        val swap = result.buttons.first { it.control == DuoScreenControl.SWAP }.rect
        assertEquals(DuoScreenControl.SWAP, result.controlAt(swap.left + 5, swap.top + 5))
        assertNull(result.controlAt(portrait.width / 2, 100))
    }

    @Test
    fun pictureInPictureFallsBackToTheFloatingButton() {
        val result = layout(pip(landscape), landscape)
        assertEquals(DuoScreenControlsLayout.Kind.FLOATING, result.kind)
        assertNull(result.panel)
        assertEquals(listOf(DuoScreenControl.MENU), result.buttons.map { it.control })
        val fab = result.buttons.single().rect
        assertEquals(landscape.width - 28 - 60, fab.left)
        assertEquals(landscape.height - 28 - 60, fab.top)
    }

    @Test
    fun floatingMenuOpensAwayFromItsCorner() {
        val bottom = layout(pip(portrait), portrait, menuOpen = true)
        val bottomFab = bottom.buttons.last().rect
        bottom.buttons.dropLast(1).forEach { assertTrue(it.rect.bottom <= bottomFab.top) }

        val top = layout(pip(portrait), portrait, menuOpen = true, corner = DuoScreenFabCorner.TOP_LEFT)
        val topFab = top.buttons.last().rect
        top.buttons.dropLast(1).forEach { assertTrue(it.rect.top >= topFab.bottom) }
        assertEquals(DuoScreenControl.MOVE, top.buttons.dropLast(1).last().control)
    }

    @Test
    fun cornersCycle() {
        var corner = DuoScreenFabCorner.BOTTOM_RIGHT
        repeat(4) { corner = corner.next() }
        assertEquals(DuoScreenFabCorner.BOTTOM_RIGHT, corner)
    }

    @Test
    fun scaleScalesTheButtons() {
        val one = layout(stacked(portrait), portrait).buttons.first().rect
        val two = DuoScreenControlsGeometry.layout(
            stacked(portrait), portrait, false, false, DuoScreenFabCorner.BOTTOM_RIGHT, scale = 2f
        ).buttons.first().rect
        assertEquals(one.width * 2, two.width)
    }

    private fun assertNotNullAndGet(rect: Rect?): Rect {
        assertNotNull(rect)
        return rect!!
    }
}
