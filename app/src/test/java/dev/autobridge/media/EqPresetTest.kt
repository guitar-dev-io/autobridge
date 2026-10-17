package dev.autobridge.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqPresetTest {
    @Test fun aFiveBandDeviceTakesThePresetAsIs() {
        val levels = EqMath.levelsFor(EqPreset.BASS.gains, intArrayOf(60, 230, 910, 3_600, 14_000), -1_500, 1_500)
        assertArrayEquals(EqPreset.BASS.gains, levels)
    }

    @Test fun otherBandsTakeTheNearestReferenceOnALogScale() {
        // 80 Hz is nearer 60 than 230 in octaves; 6 kHz is nearer 3.6k than 14k.
        val levels = EqMath.levelsFor(intArrayOf(100, 200, 300, 400, 500), intArrayOf(80, 6_000), -1_500, 1_500)
        assertArrayEquals(intArrayOf(100, 400), levels)
    }

    @Test fun levelsAreClampedToTheDeviceRange() {
        val levels = EqMath.levelsFor(intArrayOf(1_200, -1_200, 0, 0, 0), intArrayOf(60, 230), -600, 600)
        assertArrayEquals(intArrayOf(600, -600), levels)
    }

    @Test fun theCarButtonCyclesThroughEveryChipAndWraps() {
        val chips = EqPreset.chips
        assertTrue(EqPreset.CUSTOM !in chips)
        var preset = chips.first()
        repeat(chips.size) { preset = EqPreset.next(preset) }
        assertEquals(chips.first(), preset)
        // Custom is not a chip; the button moves on from it to the first one.
        assertEquals(chips.first(), EqPreset.next(EqPreset.CUSTOM))
    }
}
