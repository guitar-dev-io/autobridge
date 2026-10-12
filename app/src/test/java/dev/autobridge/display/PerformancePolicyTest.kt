package dev.autobridge.display

import dev.autobridge.display.PerformancePolicy.Level
import dev.autobridge.display.PerformancePolicy.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformancePolicyTest {
    private val policy = PerformancePolicy()

    private fun feed(fromMs: Long, toMs: Long, thermal: Int, struggling: Boolean = false, stepMs: Long = 2_000L) {
        var t = fromMs
        while (t <= toMs) {
            policy.onSample(Sample(t, thermal, struggling))
            t += stepMs
        }
    }

    @Test fun aCoolPhoneThatKeepsUpStaysNormal() {
        feed(0, 120_000, thermal = 1)
        assertEquals(Level.NORMAL, policy.level)
    }

    @Test fun moderateHeatReducesAtOnceAndSevereGoesLow() {
        assertTrue(policy.onSample(Sample(0, 2, false)))
        assertEquals(Level.REDUCED, policy.level)
        assertTrue(policy.onSample(Sample(2_000, 3, false)))
        assertEquals(Level.LOW, policy.level)
        assertEquals("thermal 3", policy.reason)
    }

    @Test fun criticalAndAboveAreLowToo() {
        listOf(4, 5, 6).forEach {
            policy.reset()
            policy.onSample(Sample(0, it, false))
            assertEquals(Level.LOW, policy.level)
        }
    }

    @Test fun aShortStruggleIsIgnored() {
        feed(0, 8_000, thermal = 0, struggling = true)
        assertEquals(Level.NORMAL, policy.level)
    }

    @Test fun aSustainedStruggleStepsDownOneLevelPerWindow() {
        feed(0, 10_000, thermal = 0, struggling = true)
        assertEquals(Level.REDUCED, policy.level)
        assertEquals("frames behind", policy.reason)
        feed(12_000, 18_000, thermal = 0, struggling = true)
        assertEquals("needs another full window", Level.REDUCED, policy.level)
        feed(20_000, 20_000, thermal = 0, struggling = true)
        assertEquals(Level.LOW, policy.level)
        feed(22_000, 60_000, thermal = 0, struggling = true)
        assertEquals("never below LOW", Level.LOW, policy.level)
    }

    @Test fun coolingStepsUpOneLevelPerMinuteNotAllAtOnce() {
        policy.onSample(Sample(0, 3, false))
        assertEquals(Level.LOW, policy.level)
        feed(2_000, 60_000, thermal = 0)
        assertEquals(Level.LOW, policy.level)
        feed(62_000, 62_000, thermal = 0)
        assertEquals(Level.REDUCED, policy.level)
        feed(64_000, 120_000, thermal = 0)
        assertEquals(Level.REDUCED, policy.level)
        feed(122_000, 124_000, thermal = 0)
        assertEquals(Level.NORMAL, policy.level)
        assertEquals("normal", policy.reason)
    }

    @Test fun aWarmBlipRestartsTheRecoveryClock() {
        policy.onSample(Sample(0, 2, false))
        feed(2_000, 50_000, thermal = 0)
        policy.onSample(Sample(52_000, 2, false))   // warm again: still REDUCED, clock resets
        feed(54_000, 100_000, thermal = 0)
        assertEquals(Level.REDUCED, policy.level)
        feed(102_000, 114_000, thermal = 0)
        assertEquals(Level.NORMAL, policy.level)
    }

    @Test fun strugglingBlocksRecovery() {
        policy.onSample(Sample(0, 2, false))
        feed(2_000, 120_000, thermal = 0, struggling = true, stepMs = 2_000)
        assertTrue(policy.level >= Level.REDUCED)
    }

    @Test fun theLevelsCapProgressively() {
        assertEquals(null, Level.NORMAL.mirrorFpsCap)
        assertTrue(Level.REDUCED.mirrorFpsCap!! > Level.LOW.mirrorFpsCap!!)
        assertTrue(Level.NORMAL.browserFrameMs < Level.REDUCED.browserFrameMs)
        assertTrue(Level.REDUCED.browserFrameMs < Level.LOW.browserFrameMs)
        assertEquals(null, Level.NORMAL.maxVideoHeight)
        assertTrue(Level.REDUCED.maxVideoHeight!! > Level.LOW.maxVideoHeight!!)
    }

    // ---------------------------------------------------------------- struggling

    @Test fun framesSkippedToHoldTheCapAreNotStruggling() {
        // 60 captured, 30 drawn on purpose at a 30fps target.
        assertFalse(PerformancePolicy.mirrorStruggling(capturedDelta = 120, renderedDelta = 60, elapsedMs = 2_000, targetFps = 30))
    }

    @Test fun aStillScreenIsNotStruggling() {
        assertFalse(PerformancePolicy.mirrorStruggling(capturedDelta = 4, renderedDelta = 4, elapsedMs = 2_000, targetFps = 60))
    }

    @Test fun framesArrivingButNotDrawnIsStruggling() {
        assertTrue(PerformancePolicy.mirrorStruggling(capturedDelta = 120, renderedDelta = 50, elapsedMs = 2_000, targetFps = 60))
    }

    @Test fun anEmptyWindowIsNotStruggling() {
        assertFalse(PerformancePolicy.mirrorStruggling(10, 0, elapsedMs = 0, targetFps = 60))
        assertFalse(PerformancePolicy.mirrorStruggling(10, 0, elapsedMs = 2_000, targetFps = 0))
    }
}
