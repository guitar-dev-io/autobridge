package dev.autobridge.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLatencyTest {

    private class FakeClock(var nanos: Long = 0) {
        fun advanceMs(ms: Long) { nanos += ms * 1_000_000 }
    }

    private fun trace(clock: FakeClock) = VoiceLatencyTrace { clock.nanos }

    @Test fun `stages are measured between their marks`() {
        val clock = FakeClock()
        val trace = trace(clock)
        trace.mark(VoiceLatencyTrace.Mark.MICROPHONE_START)
        clock.advanceMs(1_500)
        trace.mark(VoiceLatencyTrace.Mark.SPEECH_END)
        clock.advanceMs(720)
        trace.mark(VoiceLatencyTrace.Mark.ENDPOINT_DETECTED)
        clock.advanceMs(5)
        trace.mark(VoiceLatencyTrace.Mark.INFERENCE_START)
        clock.advanceMs(410)
        trace.mark(VoiceLatencyTrace.Mark.INFERENCE_END)
        clock.advanceMs(12)
        trace.mark(VoiceLatencyTrace.Mark.INTENT_RESOLVED)
        trace.mark(VoiceLatencyTrace.Mark.ACTION_DISPATCHED)
        clock.advanceMs(30)
        trace.mark(VoiceLatencyTrace.Mark.ACTION_STARTED)

        assertEquals(720L, trace.endpointMs)
        assertEquals(5L, trace.inferenceWaitMs)
        assertEquals(410L, trace.inferenceMs)
        assertEquals(12L, trace.intentMs)
        assertEquals(30L, trace.dispatchToActionMs)
        assertEquals(1_177L, trace.totalMs)
        assertEquals(1_147L, trace.speechEndToIntentMs)
    }

    @Test fun `a stage that was not reached has no duration`() {
        val clock = FakeClock()
        val trace = trace(clock)
        trace.mark(VoiceLatencyTrace.Mark.SPEECH_END)
        clock.advanceMs(500)
        trace.mark(VoiceLatencyTrace.Mark.ENDPOINT_DETECTED)
        assertEquals(500L, trace.endpointMs)
        assertNull(trace.inferenceMs)
        assertNull(trace.totalMs)
        assertTrue(trace.summary().contains("total=-"))
    }

    @Test fun `summary carries numbers and labels, never words that were spoken`() {
        val trace = VoiceLatencyTrace { 0L }.apply {
            modelId = "base-q8_0"
            language = "th"
            outcome = "action"
            threads = 4
            fallback = true
        }
        val line = trace.summary()
        assertTrue(line.contains("model=base-q8_0"))
        assertTrue(line.contains("threads=4"))
        assertTrue(line.contains("fallback=true"))
        // The trace has no field a transcript could be put in.
        assertFalse(VoiceLatencyTrace::class.java.declaredFields.any { it.name.contains("text", ignoreCase = true) })
    }

    @Test fun `finish records once`() {
        val lines = mutableListOf<String>()
        val previous = VoiceLatencyLog.sink
        VoiceLatencyLog.clear()
        VoiceLatencyLog.sink = { lines += it }
        try {
            val trace = VoiceLatencyTrace { 0L }
            trace.finish()
            trace.finish()
            assertEquals(1, lines.size)
            assertEquals(1, VoiceLatencyLog.recent().size)
        } finally {
            VoiceLatencyLog.sink = previous
            VoiceLatencyLog.clear()
        }
    }

    @Test fun `the log keeps the most recent traces only`() {
        val previous = VoiceLatencyLog.sink
        VoiceLatencyLog.clear()
        VoiceLatencyLog.sink = {}
        try {
            repeat(VoiceLatencyLog.CAPACITY + 7) { VoiceLatencyTrace { 0L }.apply { audioMs = it.toLong() }.finish() }
            val recent = VoiceLatencyLog.recent()
            assertEquals(VoiceLatencyLog.CAPACITY, recent.size)
            assertEquals(7L, recent.first().audioMs)
        } finally {
            VoiceLatencyLog.sink = previous
            VoiceLatencyLog.clear()
        }
    }

    @Test fun `percentiles are nearest rank`() {
        val p = Percentiles.of((1L..100L).toList())!!
        assertEquals(100, p.count)
        assertEquals(50L, p.p50)
        assertEquals(95L, p.p95)
        assertEquals(99L, p.p99)
        assertEquals(Percentiles(1, 7, 7, 7), Percentiles.of(listOf(7L)))
        assertNull(Percentiles.of(emptyList()))
    }

    @Test fun `report separates warm and cold commands`() {
        val clock = FakeClock()
        fun command(warm: Boolean, totalMs: Long) = trace(clock).apply {
            modelWarm = warm
            mark(VoiceLatencyTrace.Mark.SPEECH_END)
            clock.advanceMs(totalMs)
            mark(VoiceLatencyTrace.Mark.ACTION_STARTED)
        }
        val report = VoiceLatencyReport.render(listOf(command(true, 800), command(true, 900), command(false, 3_000)))!!
        assertTrue(report, report.contains("total, warm model  n=2 P50=800ms"))
        assertTrue(report, report.contains("total, cold model  n=1 P50=3000ms"))
        assertNull(VoiceLatencyReport.render(emptyList()))
    }
}
