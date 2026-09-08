package dev.autobridge.display

import android.os.SystemClock

/**
 * Lifecycle/latency diagnostics for the mirror pipeline. This is deliberately not per-frame FPS:
 * `MirrorCoordinator`'s AUTO_MIRROR path is a zero-copy, OS-level mirror, so the app never sees
 * individual frames. Real FPS would need the same self-drawn (ImageReader + Canvas) pipeline that
 * [dev.autobridge.input.DisplayTransform]'s FILL/STRETCH profiles need — a pipeline change that
 * was explicitly deferred. What's honestly measurable without that: how long each pipeline stage
 * takes to stand up, and how long mirroring actually stays active.
 */
object MirrorDiagnostics {
    data class Event(val label: String, val elapsedRealtimeMs: Long)

    private const val MAX_EVENTS = 50
    private val events = ArrayDeque<Event>()

    @Volatile
    private var mirroringActiveSinceMs: Long? = null

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
    }

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
