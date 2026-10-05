package dev.autobridge.duoscreen.layout

import dev.autobridge.duoscreen.layout.DuoScreenLayout.Bounds
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuoScreenPresetTest {
    private val bounds = Bounds(1000, 800)

    @Test fun evenColumnsSplitsTheWidthInHalf() {
        val rects = DuoScreenPreset.EVEN_COLUMNS.rects(2, bounds)
        assertEquals(Rect(0, 0, 500, 800), rects[0])
        assertEquals(Rect(500, 0, 500, 800), rects[1])
    }

    @Test fun evenRowsSplitTheHeightInHalf() {
        val rects = DuoScreenPreset.EVEN_ROWS.rects(2, bounds)
        assertEquals(Rect(0, 0, 1000, 400), rects[0])
        assertEquals(Rect(0, 400, 1000, 400), rects[1])
    }

    @Test fun wideLeftGivesTheFirstPaneTwoThirdsOfTheWidth() {
        val rects = DuoScreenPreset.WIDE_LEFT.rects(2, bounds)
        assertEquals(650, rects[0].width)
        assertEquals(350, rects[1].width)
        assertEquals(0, rects[0].left)
    }

    @Test fun wideRightMirrorsWideLeft() {
        val rects = DuoScreenPreset.WIDE_RIGHT.rects(2, bounds)
        assertEquals(350, rects[0].width)
        assertEquals(650, rects[1].width)
    }

    @Test fun pictureInPictureKeepsPaneZeroFullSize() {
        val rects = DuoScreenPreset.PICTURE_IN_PICTURE.rects(2, bounds)
        assertEquals(Rect(0, 0, 1000, 800), rects[0])
        val tile = rects[1]
        assertTrue("tile should sit inside the surface", tile.right <= bounds.width)
        assertTrue("tile should sit inside the surface", tile.bottom <= bounds.height)
        assertTrue("tile should be smaller than the surface", tile.width < bounds.width)
    }

    @Test fun pictureInPictureStacksThreePanesWithoutOverlapping() {
        val rects = DuoScreenPreset.PICTURE_IN_PICTURE.rects(3, bounds)
        assertTrue(
            "the two floating tiles must not cover each other",
            !DuoScreenLayout.overlaps(rects[1], rects[2])
        )
    }

    @Test fun everyPresetTilesTheSurfaceExactlyAtBothPaneCounts() {
        // Picture-in-picture is the one preset that deliberately overlaps rather than tiles.
        val tiling = DuoScreenPreset.entries - DuoScreenPreset.PICTURE_IN_PICTURE
        tiling.forEach { preset ->
            (2..3).forEach { count ->
                val rects = preset.rects(count, bounds)
                assertEquals("$preset/$count pane count", count, rects.size)
                assertEquals(
                    "$preset/$count must cover the surface",
                    bounds.width * bounds.height,
                    rects.sumOf { it.width * it.height }
                )
            }
        }
    }

    @Test fun noPresetProducesAPaneUnderTheMinimumSize() {
        DuoScreenPreset.entries.forEach { preset ->
            (2..3).forEach { count ->
                preset.rects(count, bounds).forEach { rect ->
                    assertEquals(
                        "$preset/$count must not need clamping",
                        rect,
                        DuoScreenLayout.clamp(rect, bounds)
                    )
                }
            }
        }
    }

    @Test fun cyclingVisitsEveryPresetAndComesBack() {
        var preset = DuoScreenPreset.EVEN_COLUMNS
        repeat(DuoScreenPreset.entries.size) { preset = preset.next() }
        assertEquals(DuoScreenPreset.EVEN_COLUMNS, preset)
    }

    @Test fun aSurfaceWithNoSizeYieldsEmptyRectsRatherThanThrowing() {
        val rects = DuoScreenPreset.EVEN_COLUMNS.rects(2, Bounds(0, 0))
        assertEquals(2, rects.size)
        assertEquals(Rect(0, 0, 0, 0), rects[0])
    }
}
