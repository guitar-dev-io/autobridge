package dev.autobridge.duoscreen.layout

import dev.autobridge.duoscreen.layout.DuoScreenLayout.Bounds
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DuoScreenLayoutCodecTest {
    private val bounds = Bounds(1000, 800)
    private val panes = listOf(
        DuoScreenPane(0, "com.example.one", Rect(0, 0, 500, 800)),
        DuoScreenPane(1, "com.example.two", Rect(500, 0, 500, 800))
    )

    @Test fun encodeThenDecodeRoundTrips() {
        val decoded = DuoScreenLayoutCodec.decode(DuoScreenLayoutCodec.encode(bounds, panes))!!
        assertEquals(bounds, decoded.bounds)
        assertEquals(panes, decoded.panes)
    }

    @Test fun decodeKeepsAnEmptyPaneEmpty() {
        val withEmpty = listOf(DuoScreenPane(0, null, Rect(0, 0, 500, 800)))
        val decoded = DuoScreenLayoutCodec.decode(DuoScreenLayoutCodec.encode(bounds, withEmpty))!!
        assertNull(decoded.panes.single().packageName)
    }

    @Test fun decodeRejectsGarbage() {
        assertNull(DuoScreenLayoutCodec.decode(null))
        assertNull(DuoScreenLayoutCodec.decode(""))
        assertNull(DuoScreenLayoutCodec.decode("not json"))
        assertNull(DuoScreenLayoutCodec.decode("""{"width":0,"height":0,"panes":[]}"""))
    }

    @Test fun refitOnTheSameBoundsOnlyClamps() {
        assertEquals(panes, DuoScreenLayoutCodec.refit(panes, bounds, bounds))
    }

    @Test fun refitScalesProportionallyToANewPanel() {
        val refitted = DuoScreenLayoutCodec.refit(panes, bounds, Bounds(2000, 1600))
        assertEquals(Rect(0, 0, 1000, 1600), refitted[0].rect)
        assertEquals(Rect(1000, 0, 1000, 1600), refitted[1].rect)
    }

    @Test fun refitKeepsPanesInsideASmallerPanel() {
        val smaller = Bounds(400, 300)
        DuoScreenLayoutCodec.refit(panes, bounds, smaller).forEach { pane ->
            assertTrue(pane.rect.left >= 0)
            assertTrue(pane.rect.top >= 0)
            assertTrue(pane.rect.right <= smaller.width)
            assertTrue(pane.rect.bottom <= smaller.height)
        }
    }

    @Test fun refitEnforcesTheMinimumPaneSize() {
        val tiny = listOf(DuoScreenPane(0, "com.example.one", Rect(0, 0, 10, 10)))
        val refitted = DuoScreenLayoutCodec.refit(tiny, bounds, bounds).single()
        assertEquals(bounds.minPaneWidth, refitted.rect.width)
        assertEquals(bounds.minPaneHeight, refitted.rect.height)
    }

    @Test fun refitKeepsThePackageOfEachPane() {
        val refitted = DuoScreenLayoutCodec.refit(panes, bounds, Bounds(1500, 900))
        assertEquals(listOf("com.example.one", "com.example.two"), refitted.map { it.packageName })
    }
}
