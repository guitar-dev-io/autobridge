package dev.autobridge.speed

import org.junit.Assert.*
import org.junit.Test

/**
 * The conflator must drop high-frequency same-state jitter (what a moving vehicle produces) while
 * never throttling away a genuine PARKED⇄MOVING transition. 0.05 m/s = 0.18 km/h is the classifier
 * boundary, so 0.0 km/h is PARKED and 0.3 km/h is MOVING.
 */
class SpeedConflatorTest {

    private fun sample(kmh: Float) = SpeedSample(kmh, SpeedOrigin.CAR, valid = true)

    @Test fun theFirstSampleAlwaysEmits() {
        val conflator = SpeedConflator(now = { 0L })
        assertTrue(conflator.shouldEmit(sample(60f)))
    }

    @Test fun aParkedToMovingTransitionEmitsEvenWithinTheIntervalAndBelowEpsilon() {
        var clock = 0L
        val conflator = SpeedConflator(minIntervalMs = 500L, epsilonKmh = 0.5f, now = { clock })
        assertTrue(conflator.shouldEmit(sample(0f)))     // PARKED, first
        clock = 10L                                       // well inside the interval
        // +0.3 km/h is below epsilon but crosses PARKED -> MOVING, so it must still emit.
        assertTrue(conflator.shouldEmit(sample(0.3f)))
    }

    @Test fun aMovingToParkedTransitionEmitsEvenWithinTheIntervalAndBelowEpsilon() {
        var clock = 0L
        val conflator = SpeedConflator(minIntervalMs = 500L, epsilonKmh = 0.5f, now = { clock })
        assertTrue(conflator.shouldEmit(sample(0.3f)))   // MOVING, first
        clock = 10L
        // Back to 0 is below epsilon from 0.3 but crosses MOVING -> PARKED, so it must emit.
        assertTrue(conflator.shouldEmit(sample(0f)))
    }

    @Test fun steadySameStateJitterUnderEpsilonAndIntervalIsSuppressed() {
        var clock = 0L
        val conflator = SpeedConflator(minIntervalMs = 500L, epsilonKmh = 0.5f, now = { clock })
        assertTrue(conflator.shouldEmit(sample(60f)))    // MOVING, first
        clock = 100L
        assertFalse(conflator.shouldEmit(sample(60.1f))) // sub-epsilon, inside interval
        clock = 200L
        assertFalse(conflator.shouldEmit(sample(60.2f)))
    }

    @Test fun aSameStateChangeAtOrAboveEpsilonAfterTheIntervalEmits() {
        var clock = 0L
        val conflator = SpeedConflator(minIntervalMs = 500L, epsilonKmh = 0.5f, now = { clock })
        assertTrue(conflator.shouldEmit(sample(60f)))    // MOVING, first
        clock = 600L                                      // past the interval
        assertTrue(conflator.shouldEmit(sample(61f)))     // >= epsilon
    }

    @Test fun aSameStateChangeAtOrAboveEpsilonBeforeTheIntervalIsSuppressed() {
        var clock = 0L
        val conflator = SpeedConflator(minIntervalMs = 500L, epsilonKmh = 0.5f, now = { clock })
        assertTrue(conflator.shouldEmit(sample(60f)))    // MOVING, first
        clock = 200L                                      // still inside the interval
        assertFalse(conflator.shouldEmit(sample(70f)))    // big delta, but interval gate holds
    }

    @Test fun deltaIsMeasuredFromTheLastPublishedValueNotTheLastSuppressedOne() {
        var clock = 0L
        val conflator = SpeedConflator(minIntervalMs = 500L, epsilonKmh = 0.5f, now = { clock })
        assertTrue(conflator.shouldEmit(sample(60f)))    // published, baseline = 60
        clock = 600L
        assertFalse(conflator.shouldEmit(sample(60.2f)))  // past interval but under epsilon vs 60
        clock = 700L
        // 60.4 is still under epsilon vs the published 60 (not vs the suppressed 60.2), so suppress.
        assertFalse(conflator.shouldEmit(sample(60.4f)))
        clock = 800L
        assertTrue(conflator.shouldEmit(sample(60.6f)))   // now >= epsilon vs the published 60
    }

    @Test fun resetForgetsHistorySoTheNextSampleEmitsAgain() {
        var clock = 0L
        val conflator = SpeedConflator(minIntervalMs = 500L, epsilonKmh = 0.5f, now = { clock })
        assertTrue(conflator.shouldEmit(sample(60f)))
        clock = 100L
        assertFalse(conflator.shouldEmit(sample(60.1f)))
        conflator.reset()
        assertTrue(conflator.shouldEmit(sample(60.1f)))   // first again after reset
    }
}
