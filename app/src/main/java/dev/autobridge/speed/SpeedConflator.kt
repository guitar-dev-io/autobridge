package dev.autobridge.speed

import dev.autobridge.safety.ParkingStateStore
import dev.autobridge.safety.SpeedPolicy
import kotlin.math.abs

/**
 * Decides whether a candidate speed sample is worth publishing, so a fast stream of samples (which
 * is what a moving vehicle produces) does not translate into continuous [SpeedManager] StateFlow
 * churn on the main thread.
 *
 * The rule, applied per candidate against the last published one:
 *  - the first candidate always publishes;
 *  - a candidate whose parking classification (PARKED/MOVING/UNKNOWN) differs from the last
 *    published one always publishes immediately — a genuine zero⇄moving transition is never
 *    throttled away, even within the interval or below epsilon;
 *  - otherwise it publishes only when at least [minIntervalMs] has elapsed since the last publish
 *    AND the km/h delta from the last published value is at least [epsilonKmh]. Steady cruising
 *    (same classification, sub-epsilon jitter, inside the interval) is dropped.
 *
 * This mirrors [SpeedPolicy] by classifying on m/s (`kmh / 3.6`), so the PARKED_EPSILON_MPS = 0.05
 * boundary that matters to the real safety path is honoured here too. It never touches
 * [ParkingStateStore] or [SpeedPolicy] state; it only reads the classifier to protect the one
 * transition that must always emit.
 *
 * Pure logic, primitives only — no allocation per call. The injected [now] clock keeps it testable
 * on the JVM (no android.os.SystemClock).
 */
class SpeedConflator(
    private val minIntervalMs: Long = 500L,
    private val epsilonKmh: Float = 0.5f,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private var hasEmitted = false
    private var lastEmitMs = 0L
    private var lastKmh = 0f
    private var lastState: ParkingStateStore.State = ParkingStateStore.State.UNKNOWN

    /**
     * Whether [candidate] should be published now. When it returns true the candidate becomes the
     * new "last published" baseline; when false, nothing is recorded and the next candidate is
     * judged against the same baseline.
     */
    fun shouldEmit(candidate: SpeedSample): Boolean {
        val state = SpeedPolicy.classify(candidate.kmh / 3.6f)
        val emit = when {
            !hasEmitted -> true
            state != lastState -> true
            now() - lastEmitMs < minIntervalMs -> false
            else -> abs(candidate.kmh - lastKmh) >= epsilonKmh
        }
        if (emit) {
            hasEmitted = true
            lastEmitMs = now()
            lastKmh = candidate.kmh
            lastState = state
        }
        return emit
    }

    /** Forgets all history, so a fresh acquire starts clean. */
    fun reset() {
        hasEmitted = false
        lastEmitMs = 0L
        lastKmh = 0f
        lastState = ParkingStateStore.State.UNKNOWN
    }
}
