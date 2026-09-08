package dev.autobridge.display

import android.os.SystemClock
import android.util.Log

/**
 * A small in-memory, structured log the developer overlay can render on top of the phone UI,
 * without needing `adb logcat`. It mirrors selected events to Logcat too, but its point is that
 * the last N entries are queryable at runtime (for a status card / overlay) and formatted for
 * display — the same "observable at runtime" motivation as [MirrorDiagnostics], which tracks
 * pipeline lifecycle/latency rather than free-form messages.
 *
 * Pure formatting and the ring-buffer bound are unit-tested; the Logcat mirror is a side effect
 * only.
 */
object StructuredLog {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(
        val level: Level,
        val tag: String,
        val message: String,
        val elapsedRealtimeMs: Long
    )

    private const val MAX_ENTRIES = 100
    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun log(level: Level, tag: String, message: String, nowMs: Long = SystemClock.elapsedRealtime()) {
        entries.addLast(Entry(level, tag, message, nowMs))
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
        when (level) {
            Level.DEBUG -> Log.d(tag, message)
            Level.INFO -> Log.i(tag, message)
            Level.WARN -> Log.w(tag, message)
            Level.ERROR -> Log.e(tag, message)
        }
    }

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(Level.INFO, tag, message)
    fun w(tag: String, message: String) = log(Level.WARN, tag, message)
    fun e(tag: String, message: String) = log(Level.ERROR, tag, message)

    @Synchronized
    fun recent(): List<Entry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()

    /**
     * Renders the most recent [limit] entries at or above [minLevel], newest last, one per line,
     * as `LEVEL/tag: message`. Pure over the supplied [source] so it's testable without the buffer.
     */
    fun format(
        source: List<Entry> = recent(),
        minLevel: Level = Level.DEBUG,
        limit: Int = 20
    ): String =
        source.asSequence()
            .filter { it.level.ordinal >= minLevel.ordinal }
            .toList()
            .takeLast(limit)
            .joinToString("\n") { "${it.level.name.first()}/${it.tag}: ${it.message}" }
}
