package dev.autobridge.duoscreen.layout

import dev.autobridge.duoscreen.layout.DuoScreenLayout.Bounds
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class DuoScreenChromeTest {
    private val portrait = Bounds(1074, 1410)

    @Test
    fun stackedPanesGetAMarginAndAGutter() {
        val panes = listOf(
            DuoScreenPane(0, "a", Rect(0, 0, 1074, 790)),
            DuoScreenPane(1, "b", Rect(0, 790, 1074, 620))
        )
        val visual = DuoScreenChrome.visualRects(panes, portrait)
        // 12 px from the surface edge, 26 px (half the 52 px gutter) from the seam.
        assertEquals(Rect(12, 12, 1050, 790 - 12 - 26), visual[0])
        assertEquals(Rect(12, 790 + 26, 1050, 620 - 26 - 12), visual[1])
    }

    @Test
    fun aFloatingTileKeepsItsOwnEdges() {
        val tile = Rect(700, 1100, 340, 280)
        val panes = listOf(
            DuoScreenPane(0, "a", Rect(0, 0, 1074, 1410)),
            DuoScreenPane(1, "b", tile)
        )
        val visual = DuoScreenChrome.visualRects(panes, portrait)
        assertEquals(Rect(12, 12, 1050, 1386), visual[0])
        assertEquals(tile, visual[1])
    }

    @Test
    fun sizesScaleWithTheShortSide() {
        assertEquals(1f, DuoScreenChrome.scale(portrait))
        assertEquals(52, DuoScreenChrome.gutter(portrait))
        // Three quarters of the design's short side: three quarters of the gutter.
        assertEquals(39, DuoScreenChrome.gutter(Bounds(1400, 806)))
        // Never below 60 %, so a small panel keeps usable controls.
        assertEquals(0.6f, DuoScreenChrome.scale(Bounds(800, 400)))
    }
}
