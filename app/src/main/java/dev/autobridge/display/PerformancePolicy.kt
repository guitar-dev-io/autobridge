package dev.autobridge.display

/**
 * Decides how hard the phone may work for the car, from how hot it is and whether the self-drawn
 * mirror keeps up.
 *
 * A long drive with mirroring, the car browser or video makes the phone hot, and a hot phone
 * throttles itself into dropped frames and stutter anyway. Stepping down first — fewer frames
 * drawn, a lower video resolution — costs less than letting the OS do it, and keeps audio and
 * touch responsive. Each [Level] says what it caps; consumers read the current one.
 *
 * Rules:
 * - Thermal status at or above MODERATE asks for [Level.REDUCED], at or above SEVERE for
 *   [Level.LOW]. Stepping down happens on the first sample that asks for it.
 * - The mirror struggling (frames arrive but well under target get drawn) for [STRUGGLE_MS] in a
 *   row steps down one more level, then needs another full window before stepping again.
 * - Stepping back up is one level at a time, only after [RECOVER_MS] of samples that are cool and
 *   not struggling, so a phone hovering at the edge does not flip back and forth.
 *
 * Pure and clock-driven by its samples, so the whole behaviour is a JVM test.
 */
class PerformancePolicy {

    enum class Level(
        /** Frame-rate ceiling for the self-drawn mirror; null leaves it as configured. */
        val mirrorFpsCap: Int?,
        /** Repaint interval of the car browser while something moves. */
        val browserFrameMs: Long,
        /** Highest video resolution (height) adaptive streams may pick; null is unlimited. */
        val maxVideoHeight: Int?
    ) {
        NORMAL(mirrorFpsCap = null, browserFrameMs = 33L, maxVideoHeight = null),
        REDUCED(mirrorFpsCap = 30, browserFrameMs = 50L, maxVideoHeight = 720),
        LOW(mirrorFpsCap = 20, browserFrameMs = 66L, maxVideoHeight = 480)
    }

    /** One reading: [PowerManager] thermal status (0–6) and whether the mirror fell behind. */
    data class Sample(val atMs: Long, val thermalStatus: Int, val mirrorStruggling: Boolean)

    var level: Level = Level.NORMAL
        private set

    /** Why [level] is what it is, for the log and the diagnostics row. */
    var reason: String = "normal"
        private set

    private var struggleSinceMs: Long? = null
    private var calmSinceMs: Long? = null

    /** Feeds one sample; returns true when [level] changed. */
    fun onSample(sample: Sample): Boolean {
        val before = level
        val thermalLevel = thermalLevel(sample.thermalStatus)

        // Struggle has to persist; a single slow second (a page load, a GC) is not a trend.
        struggleSinceMs = if (sample.mirrorStruggling) struggleSinceMs ?: sample.atMs else null
        val sustainedStruggle = struggleSinceMs?.let { sample.atMs - it >= STRUGGLE_MS } == true

        when {
            thermalLevel > level -> {
                level = thermalLevel
                reason = "thermal ${sample.thermalStatus}"
                calmSinceMs = null
            }
            sustainedStruggle && level < Level.LOW -> {
                level = Level.entries[level.ordinal + 1]
                reason = "frames behind"
                struggleSinceMs = sample.atMs // a further step needs another full window
                calmSinceMs = null
            }
            level > thermalLevel && !sample.mirrorStruggling -> {
                val calmSince = calmSinceMs ?: sample.atMs.also { calmSinceMs = it }
                if (sample.atMs - calmSince >= RECOVER_MS) {
                    level = Level.entries[level.ordinal - 1]
                    reason = if (level == Level.NORMAL) "normal" else "recovering"
                    calmSinceMs = sample.atMs
                }
            }
            else -> calmSinceMs = null
        }
        return level != before
    }

    fun reset() {
        level = Level.NORMAL
        reason = "normal"
        struggleSinceMs = null
        calmSinceMs = null
    }

    companion object {
        /** Mirrors `PowerManager.THERMAL_STATUS_MODERATE` / `_SEVERE`, kept here so this stays pure. */
        const val THERMAL_MODERATE = 2
        const val THERMAL_SEVERE = 3
        const val STRUGGLE_MS = 10_000L
        const val RECOVER_MS = 60_000L

        fun thermalLevel(status: Int): Level = when {
            status >= THERMAL_SEVERE -> Level.LOW
            status >= THERMAL_MODERATE -> Level.REDUCED
            else -> Level.NORMAL
        }

        /**
         * Whether the self-drawn mirror is falling behind over one sampling window. Its dropped
         * counter also counts frames skipped on purpose to hold the frame-rate cap, so it cannot be
         * used directly; instead: frames are arriving at least at the target rate, yet fewer than
         * 70% of the target get drawn. A still phone screen sends few frames and is not struggling.
         */
        fun mirrorStruggling(capturedDelta: Long, renderedDelta: Long, elapsedMs: Long, targetFps: Int): Boolean {
            if (elapsedMs <= 0 || targetFps <= 0) return false
            val capturedFps = capturedDelta * 1000f / elapsedMs
            val renderedFps = renderedDelta * 1000f / elapsedMs
            return capturedFps >= targetFps * 0.9f && renderedFps < targetFps * 0.7f
        }
    }
}
