package dev.autobridge.voice

import android.os.SystemClock
import java.util.Locale

/**
 * Where the time goes in one voice command, from the microphone opening to the action starting.
 *
 * Every mark is `SystemClock.elapsedRealtimeNanos()` (monotonic, survives deep sleep), taken on
 * whatever thread reached that point. [SPEECH_START] and [SPEECH_END] are derived from audio time
 * rather than read from the clock - the detector only knows *which frame* speech started or ended
 * in, and frames reach it in bursts - so [Mark.SPEECH_END] is "[Mark.ENDPOINT] minus the silence the
 * detector counted", which is exactly the wait the endpointing setting controls.
 *
 * Holds numbers and short labels only: never audio, never the transcript.
 */
class VoiceLatencyTrace(private val clock: () -> Long = VoiceClock::now) {

    enum class Mark {
        MICROPHONE_START,
        SPEECH_START,
        SPEECH_END,
        ENDPOINT_DETECTED,
        INFERENCE_START,
        INFERENCE_END,
        INTENT_RESOLVED,
        ACTION_DISPATCHED,
        ACTION_STARTED
    }

    private val marks = LongArray(Mark.entries.size) { UNSET }

    var modelId: String = ""
    var audioMs: Long = 0
    var threads: Int = 0
    /** `PowerManager.THERMAL_STATUS_*` when the inference started; -1 when unknown. */
    var thermalStatus: Int = -1
    /** The model was already resident when the microphone opened. */
    var modelWarm: Boolean = false
    /** Model load paid by this command (0 when it was already loaded). */
    var modelLoadMs: Long = 0
    /** One-off warm-up pass after a load, paid by this command. */
    var warmUpMs: Long = 0
    var language: String = ""
    var encodeMs: Float = 0f
    var decodeMs: Float = 0f
    /** Whisper fell back to sampling at a higher temperature (a second decode). */
    var fallback: Boolean = false
    var ending: String = ""
    /** "car" or "phone" microphone. */
    var source: String = ""
    var carConnected: Boolean = false
    var mirroring: Boolean = false
    /** How the command finished: "action", "unknown", "refused", or a [VoiceError] name. */
    var outcome: String = ""

    fun mark(mark: Mark) = markAt(mark, clock())

    fun markAt(mark: Mark, nanos: Long) {
        marks[mark.ordinal] = nanos
    }

    fun at(mark: Mark): Long? = marks[mark.ordinal].takeIf { it != UNSET }

    fun now(): Long = clock()

    private var finished = false

    /** Hands the trace to [VoiceLatencyLog]; only the first call counts. */
    fun finish(log: VoiceLatencyLog = VoiceLatencyLog) {
        synchronized(this) {
            if (finished) return
            finished = true
        }
        log.record(this)
    }

    /** Milliseconds from [from] to [to], or null when either was not reached. */
    fun between(from: Mark, to: Mark): Long? {
        val start = at(from) ?: return null
        val end = at(to) ?: return null
        return (end - start) / 1_000_000
    }

    /** Last speech frame to the detector calling it the end. */
    val endpointMs: Long? get() = between(Mark.SPEECH_END, Mark.ENDPOINT_DETECTED)
    /** Endpoint to the native call starting: waiting for a model load still in progress, mostly. */
    val inferenceWaitMs: Long? get() = between(Mark.ENDPOINT_DETECTED, Mark.INFERENCE_START)
    val inferenceMs: Long? get() = between(Mark.INFERENCE_START, Mark.INFERENCE_END)
    /** Transcript to a parsed, validated command, including the hop to the main thread. */
    val intentMs: Long? get() = between(Mark.INFERENCE_END, Mark.INTENT_RESOLVED)
    val dispatchToActionMs: Long? get() = between(Mark.ACTION_DISPATCHED, Mark.ACTION_STARTED)
    /** The number that matters: the speaker stopped, the car started doing it. */
    val totalMs: Long? get() = between(Mark.SPEECH_END, Mark.ACTION_STARTED)
    /** Speaker stopped to command understood; also defined when nothing was run (test screen). */
    val speechEndToIntentMs: Long? get() = between(Mark.SPEECH_END, Mark.INTENT_RESOLVED)

    /** One log line, numbers only. */
    fun summary(): String = buildString {
        append("voice latency")
        append(" outcome=").append(outcome.ifEmpty { "?" })
        append(" total=").append(ms(totalMs))
        append(" endpoint=").append(ms(endpointMs))
        append(" wait=").append(ms(inferenceWaitMs))
        append(" inference=").append(ms(inferenceMs))
        append(" intent=").append(ms(intentMs))
        append(" dispatch=").append(ms(dispatchToActionMs))
        append(" model=").append(modelId.ifEmpty { "?" })
        append(" warm=").append(modelWarm)
        append(" load=").append(modelLoadMs).append("ms")
        append(" warmup=").append(warmUpMs).append("ms")
        append(" audio=").append(audioMs).append("ms")
        append(" threads=").append(threads)
        append(" lang=").append(language.ifEmpty { "?" })
        append(" encode=").append(String.format(Locale.US, "%.0f", encodeMs)).append("ms")
        append(" decode=").append(String.format(Locale.US, "%.1f", decodeMs)).append("ms/tok")
        append(" fallback=").append(fallback)
        append(" thermal=").append(thermalStatus)
        append(" ending=").append(ending.ifEmpty { "?" })
        append(" source=").append(source.ifEmpty { "?" })
        append(" car=").append(carConnected)
        append(" mirroring=").append(mirroring)
    }

    private fun ms(value: Long?) = if (value == null) "-" else "${value}ms"

    companion object {
        private const val UNSET = Long.MIN_VALUE
    }
}

/** The monotonic clock the traces use. A seam so JVM tests never touch the android.jar stub. */
object VoiceClock {
    fun now(): Long = SystemClock.elapsedRealtimeNanos()
}

/**
 * The last [CAPACITY] finished traces, for the Test Voice Recognition screen and Send log, plus
 * percentiles over them. In memory only: the numbers are for whoever is debugging this session.
 */
object VoiceLatencyLog {
    const val CAPACITY = 100

    private val traces = ArrayDeque<VoiceLatencyTrace>()

    @Volatile
    var sink: (String) -> Unit = { dev.autobridge.logging.StructuredLog.i(VoiceRuntime.TAG, it) }

    @Synchronized
    fun record(trace: VoiceLatencyTrace) {
        if (traces.size == CAPACITY) traces.removeFirst()
        traces.addLast(trace)
        sink(trace.summary())
    }

    @Synchronized
    fun recent(): List<VoiceLatencyTrace> = traces.toList()

    @Synchronized
    fun clear() = traces.clear()

    /** P50/P95/P99 of [metric] over the traces that have it; null when there are none. */
    fun percentiles(metric: (VoiceLatencyTrace) -> Long?): Percentiles? =
        Percentiles.of(recent().mapNotNull(metric))
}

/** The text the Test Voice Recognition screen shows for [VoiceLatencyLog]. */
object VoiceLatencyReport {
    fun render(traces: List<VoiceLatencyTrace>): String? {
        if (traces.isEmpty()) return null
        fun row(label: String, metric: (VoiceLatencyTrace) -> Long?): String? =
            Percentiles.of(traces.mapNotNull(metric))?.let { "${label.padEnd(18)} $it" }
        val warm = traces.filter { it.modelWarm }
        val cold = traces.filter { !it.modelWarm }
        return listOfNotNull(
            "last: " + traces.last().summary().removePrefix("voice latency "),
            "",
            row("total", VoiceLatencyTrace::totalMs),
            row("total, warm model", { t -> t.totalMs.takeIf { t in warm } }),
            row("total, cold model", { t -> t.totalMs.takeIf { t in cold } }),
            row("speech end→intent", VoiceLatencyTrace::speechEndToIntentMs),
            row("endpoint", VoiceLatencyTrace::endpointMs),
            row("model wait", VoiceLatencyTrace::inferenceWaitMs),
            row("inference", VoiceLatencyTrace::inferenceMs),
            row("intent", VoiceLatencyTrace::intentMs),
            row("dispatch→action", VoiceLatencyTrace::dispatchToActionMs),
            row("model load", { t -> t.modelLoadMs.takeIf { it > 0 } }),
            "fallbacks: ${traces.count { it.fallback }} of ${traces.size}"
        ).joinToString("\n")
    }
}

data class Percentiles(val count: Int, val p50: Long, val p95: Long, val p99: Long) {
    override fun toString(): String = "n=$count P50=${p50}ms P95=${p95}ms P99=${p99}ms"

    companion object {
        /** Nearest-rank percentiles; null for no samples. */
        fun of(samples: List<Long>): Percentiles? {
            if (samples.isEmpty()) return null
            val sorted = samples.sorted()
            fun rank(p: Double): Long {
                val index = kotlin.math.ceil(p * sorted.size).toInt().coerceIn(1, sorted.size) - 1
                return sorted[index]
            }
            return Percentiles(sorted.size, rank(0.50), rank(0.95), rank(0.99))
        }
    }
}
