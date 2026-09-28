package dev.autobridge.display

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * Lifecycle and frame diagnostics for the mirror pipeline. AUTO_MIRROR remains zero-copy and does not
 * expose frames to app code; SELF_DRAWN adds bounded ImageReader/Canvas counters and latency while
 * keeping the same lifecycle event ring. The counters are measurements of the self-drawn path, not
 * a claim that AUTO_MIRROR has an app-observable FPS.
 */
object MirrorDiagnostics {
    data class Event(val label: String, val elapsedRealtimeMs: Long)

    private const val MAX_EVENTS = 50
    private val events = ArrayDeque<Event>()

    @Volatile
    private var mirroringActiveSinceMs: Long? = null

    data class FrameStats(
        val captured: Long,
        val dropped: Long,
        val rendered: Long,
        val measuredFps: Float,
        val lastLatencyMs: Long?
    )

    private val framesCaptured = AtomicLong()
    private val framesDropped = AtomicLong()
    private val framesRendered = AtomicLong()
    @Volatile
    private var measuredFps = 0f
    @Volatile
    private var lastLatencyMs: Long? = null
    private var fpsWindowStartMs = 0L
    private var fpsWindowRendered = 0L

    @Synchronized
    fun record(label: String, nowMs: Long = SystemClock.elapsedRealtime()) {
        events.addLast(Event(label, nowMs))
        while (events.size > MAX_EVENTS) events.removeFirst()
    }

    @Synchronized
    fun recent(): List<Event> = events.toList()

    /** Clears the event ring while preserving the active-mirroring clock. */
    @Synchronized
    fun clearEvents() {
        events.clear()
    }

    @Synchronized
    fun reset() {
        events.clear()
        mirroringActiveSinceMs = null
        resetFrameStats()
    }

    fun resetFrameStats() {
        framesCaptured.set(0)
        framesDropped.set(0)
        framesRendered.set(0)
        measuredFps = 0f
        lastLatencyMs = null
        synchronized(this) {
            fpsWindowStartMs = 0L
            fpsWindowRendered = 0L
        }
    }

    fun recordFrameCaptured() {
        framesCaptured.incrementAndGet()
    }

    fun recordFrameDropped(count: Long = 1L) {
        if (count > 0L) framesDropped.addAndGet(count)
    }

    @Synchronized
    fun recordFrameRendered(latencyMs: Long?, nowMs: Long = SystemClock.elapsedRealtime()) {
        framesRendered.incrementAndGet()
        lastLatencyMs = latencyMs
        if (fpsWindowStartMs == 0L) fpsWindowStartMs = nowMs
        fpsWindowRendered += 1
        val elapsed = nowMs - fpsWindowStartMs
        if (elapsed >= 1_000L) {
            measuredFps = fpsWindowRendered * 1_000f / elapsed
            fpsWindowStartMs = nowMs
            fpsWindowRendered = 0L
        }
    }

    fun frameStats(): FrameStats = FrameStats(
        captured = framesCaptured.get(),
        dropped = framesDropped.get(),
        rendered = framesRendered.get(),
        measuredFps = measuredFps,
        lastLatencyMs = lastLatencyMs
    )

    /** Call whenever [dev.autobridge.mirror.MirrorCoordinator.isMirroring] flips. */
    fun onMirroringActiveChanged(isActive: Boolean, nowMs: Long = SystemClock.elapsedRealtime()) {
        if (isActive) {
            if (mirroringActiveSinceMs == null) {
                mirroringActiveSinceMs = nowMs
                record("mirroring_active", nowMs)
            }
        } else if (mirroringActiveSinceMs != null) {
            record("mirroring_inactive", nowMs)
            mirroringActiveSinceMs = null
        }
    }

    fun currentMirroringUptimeMs(nowMs: Long = SystemClock.elapsedRealtime()): Long? =
        mirroringActiveSinceMs?.let { (nowMs - it).coerceAtLeast(0) }

    /** Elapsed time between the most recent occurrence of each label, or null if either hasn't happened (yet). */
    @Synchronized
    fun latencyBetween(fromLabel: String, toLabel: String): Long? {
        val from = events.lastOrNull { it.label == fromLabel } ?: return null
        val to = events.lastOrNull { it.label == toLabel } ?: return null
        return (to.elapsedRealtimeMs - from.elapsedRealtimeMs).takeIf { it >= 0 }
    }

    /** Formats the event ring for the in-app developer screen without relying on logcat. */
    fun format(source: List<Event> = recent(), limit: Int = 20): String =
        source.takeLast(limit.coerceAtLeast(0))
            .joinToString("\n") { "${it.label} @ ${it.elapsedRealtimeMs}ms" }
}
