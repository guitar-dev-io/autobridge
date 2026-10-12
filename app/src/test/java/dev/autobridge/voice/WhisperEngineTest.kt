package dev.autobridge.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WhisperEngineTest {

    private class FakeBackend(var supported: Boolean = true) : WhisperBackend {
        val events = mutableListOf<String>()
        var nextHandle = 1L
        var failLoad = false
        var failTranscribe = false
        val live = mutableSetOf<Long>()

        override fun prepare() = supported
        override fun unavailableReason() = "no arm64 library"
        override fun load(path: String): Long {
            events += "load ${File(path).name}"
            if (failLoad) return 0
            return nextHandle++.also { live += it }
        }
        override fun free(handle: Long) {
            events += "free $handle"
            live -= handle
        }
        override fun abort(handle: Long) {
            events += "abort $handle"
            aborted += handle
        }
        var warmUps = 0
        var fallbackUsed = false
        /** Runs inside the native call, to act while a transcription is in flight. */
        var duringTranscribe: () -> Unit = {}
        val aborted = mutableSetOf<Long>()
        val maxFallbacksSeen = mutableListOf<Int>()

        override fun resetAbort(handle: Long) { aborted -= handle }
        override fun warmUp(handle: Long, threads: Int): Boolean {
            events += "warmup $handle"
            warmUps++
            return true
        }
        override fun transcribe(
            handle: Long, pcm: FloatArray, language: String, translate: Boolean, threads: Int,
            prompt: String?, maxFallbacks: Int
        ): String? {
            check(handle in live) { "transcribe on freed handle" }
            events += "transcribe $handle $language"
            maxFallbacksSeen += maxFallbacks
            duringTranscribe()
            if (handle in aborted) return null
            return if (failTranscribe) null else " เปิด YouTube เพลง Bodyslam"
        }
        override fun lastLanguage(handle: Long) = "th"
        override fun lastTimings(handle: Long) = floatArrayOf(300f, 120f, 1f, 0f, if (fallbackUsed) 40f else 0f)
    }

    private val base = WhisperModelCatalog.find("base-q8_0")!!
    private val tiny = WhisperModelCatalog.find("tiny-q8_0")!!
    private val baseFile = File("/models/ggml-base-q8_0.bin")
    private val tinyFile = File("/models/ggml-tiny-q8_0.bin")
    private val threeSeconds = FloatArray(48_000)

    private fun engine(backend: FakeBackend) = WhisperEngine(backend, Dispatchers.IO)

    private fun WhisperEngine.run(model: WhisperModelInfo, file: File) = runBlocking {
        transcribe(model, file, threeSeconds, VoiceLanguage.AUTO, translate = false, threads = 4, prompt = null)
    }

    @Test fun `model is loaded once and reused`() {
        val backend = FakeBackend()
        val engine = engine(backend)
        val first = engine.run(base, baseFile)
        val second = engine.run(base, baseFile)
        assertEquals(1, backend.events.count { it.startsWith("load") })
        assertEquals(2, backend.events.count { it.startsWith("transcribe") })
        assertEquals(" เปิด YouTube เพลง Bodyslam", first.text)
        assertEquals("th", first.language)
        assertEquals(3_000, first.audioMs)
        assertEquals(0L, second.modelLoadMs)
        assertEquals(base.id, engine.loadedModelId)
    }

    @Test fun `switching model frees the old context before loading the new one`() {
        val backend = FakeBackend()
        val engine = engine(backend)
        engine.run(base, baseFile)
        engine.run(tiny, tinyFile)
        assertEquals(
            listOf(
                "load ggml-base-q8_0.bin", "warmup 1", "transcribe 1 auto",
                "free 1", "load ggml-tiny-q8_0.bin", "warmup 2", "transcribe 2 auto"
            ),
            backend.events
        )
        assertEquals(setOf(2L), backend.live)
        assertEquals(tiny.id, engine.loadedModelId)
    }

    @Test fun `preload is reported once by the request that used it`() = runBlocking {
        val backend = FakeBackend()
        val engine = engine(backend)
        engine.ensureLoaded(base, baseFile)
        assertTrue(engine.state.value is WhisperEngine.State.Ready)
        engine.run(base, baseFile)
        assertEquals(1, backend.events.count { it.startsWith("load") })
    }

    @Test fun `release frees and the next request loads again`() = runBlocking {
        val backend = FakeBackend()
        val engine = engine(backend)
        engine.run(base, baseFile)
        engine.release()
        assertEquals(WhisperEngine.State.Unloaded, engine.state.value)
        assertTrue(backend.live.isEmpty())
        assertNull(engine.loadedModelId)
        engine.run(base, baseFile)
        assertEquals(2, backend.events.count { it.startsWith("load") })
    }

    @Test fun `load failure is reported as model load failed`() {
        val backend = FakeBackend().apply { failLoad = true }
        val engine = engine(backend)
        assertEquals(WhisperException.Reason.MODEL_LOAD_FAILED, reasonOf { engine.run(base, baseFile) })
        assertEquals(WhisperEngine.State.Failed(base.id, WhisperException.Reason.MODEL_LOAD_FAILED), engine.state.value)
    }

    @Test fun `unsupported device never reaches native code`() {
        val backend = FakeBackend(supported = false)
        val engine = engine(backend)
        assertEquals(WhisperException.Reason.UNSUPPORTED_DEVICE, reasonOf { engine.run(base, baseFile) })
        assertTrue(backend.events.isEmpty())
    }

    @Test fun `transcription failure keeps the model loaded`() {
        val backend = FakeBackend().apply { failTranscribe = true }
        val engine = engine(backend)
        assertEquals(WhisperException.Reason.TRANSCRIPTION_FAILED, reasonOf { engine.run(base, baseFile) })
        assertEquals(base.id, engine.loadedModelId)
        assertTrue(!engine.isBusy)
    }

    @Test fun `real time factor`() {
        val result = Transcription("x", "th", "base-q8_0", audioMs = 3_200, processingMs = 840, encodeMs = 0f, decodeMs = 0f, threads = 4, modelLoadMs = 0)
        assertEquals(0.2625, result.realTimeFactor, 1e-9)
    }

    // --- latency: warm-up, cancellation, contention ------------------------------------------------

    @Test fun `auto-detect warms a fresh context up once, before its first request`() {
        val backend = FakeBackend()
        val engine = engine(backend)
        engine.run(base, baseFile)
        engine.run(base, baseFile)
        assertEquals(listOf("load ggml-base-q8_0.bin", "warmup 1", "transcribe 1 auto", "transcribe 1 auto"), backend.events)
    }

    @Test fun `a fixed language skips the warm-up`() = runBlocking {
        val backend = FakeBackend()
        val engine = engine(backend)
        engine.ensureLoaded(base, baseFile, VoiceLanguage.THAI)
        engine.transcribe(base, baseFile, threeSeconds, VoiceLanguage.THAI, translate = false, threads = 4, prompt = null)
        assertEquals(0, backend.warmUps)
    }

    @Test fun `a reload warms up again`() = runBlocking {
        val backend = FakeBackend()
        val engine = engine(backend)
        engine.run(base, baseFile)
        engine.release()
        engine.run(base, baseFile)
        assertEquals(2, backend.warmUps)
    }

    @Test fun `the warm-up of a preload is reported by the first request only`() = runBlocking {
        val backend = FakeBackend()
        val engine = engine(backend)
        engine.ensureLoaded(base, baseFile, VoiceLanguage.AUTO)
        assertEquals(1, backend.warmUps)
        engine.run(base, baseFile)
        val second = engine.run(base, baseFile)
        assertEquals(1, backend.warmUps)
        assertEquals(0L, second.warmUpMs)
        assertEquals(0L, second.modelLoadMs)
    }

    @Test fun `bounded fallback is the default and a fallback is reported`() {
        val backend = FakeBackend().apply { fallbackUsed = true }
        val engine = engine(backend)
        val result = engine.run(base, baseFile)
        assertEquals(listOf(WhisperEngine.DEFAULT_MAX_FALLBACKS), backend.maxFallbacksSeen)
        assertEquals(1, WhisperEngine.DEFAULT_MAX_FALLBACKS)
        assertTrue(result.fallback)
    }

    @Test fun `a request cancelled while queued never reaches native code`() = runBlocking {
        val backend = FakeBackend()
        val engine = WhisperEngine(backend, Executors.newSingleThreadExecutor().asCoroutineDispatcher())
        engine.ensureLoaded(base, baseFile)
        val firstRunning = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        backend.duringTranscribe = {
            firstRunning.countDown()
            releaseFirst.await(5, TimeUnit.SECONDS)
        }
        val first = async(Dispatchers.IO) { engine.run(base, baseFile) }
        assertTrue(firstRunning.await(5, TimeUnit.SECONDS))
        backend.duringTranscribe = {}
        val stale = WhisperEngine.Ticket()
        val second = async(Dispatchers.IO) {
            runCatching {
                engine.transcribe(base, baseFile, threeSeconds, VoiceLanguage.AUTO, false, 4, null, ticket = stale)
            }
        }
        engine.abort(stale)
        releaseFirst.countDown()
        first.await()
        val error = second.await().exceptionOrNull() as? WhisperException
        assertEquals(WhisperException.Reason.ABORTED, error?.reason)
        assertEquals(1, backend.events.count { it.startsWith("transcribe") })
    }

    @Test fun `a running request is aborted through its ticket`() {
        val backend = FakeBackend()
        val engine = engine(backend)
        val ticket = WhisperEngine.Ticket()
        backend.duringTranscribe = { engine.abort(ticket) }
        val reason = reasonOf {
            runBlocking {
                engine.transcribe(base, baseFile, threeSeconds, VoiceLanguage.AUTO, false, 4, null, ticket = ticket)
            }
        }
        assertEquals(WhisperException.Reason.ABORTED, reason)
        // The next command runs normally: the flag is cleared before every request.
        backend.duringTranscribe = {}
        assertEquals(" เปิด YouTube เพลง Bodyslam", engine.run(base, baseFile).text)
        assertTrue(!engine.isBusy)
    }

    @Test fun `aborting a finished request is harmless`() {
        val backend = FakeBackend()
        val engine = engine(backend)
        val ticket = WhisperEngine.Ticket()
        runBlocking { engine.transcribe(base, baseFile, threeSeconds, VoiceLanguage.AUTO, false, 4, null, ticket = ticket) }
        engine.abort(ticket)
        assertTrue(backend.events.none { it.startsWith("abort") })
    }

    @Test fun `rapid consecutive commands never run inference at the same time`() = runBlocking {
        val backend = FakeBackend()
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        backend.duringTranscribe = {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            Thread.sleep(5)
            inFlight.decrementAndGet()
        }
        val engine = WhisperEngine(backend, Executors.newSingleThreadExecutor().asCoroutineDispatcher())
        val jobs = List(6) { async(Dispatchers.IO) { engine.run(base, baseFile) } }
        jobs.forEach { it.await() }
        assertEquals(1, maxInFlight.get())
        assertEquals(6, backend.events.count { it.startsWith("transcribe") })
        assertEquals(1, backend.events.count { it.startsWith("load") })
    }

    @Test fun `a lease keeps the engine in use until it is closed`() {
        val engine = engine(FakeBackend())
        assertTrue(!engine.isInUse)
        val lease = engine.lease()
        val other = engine.lease()
        assertTrue(engine.isInUse)
        lease.close()
        lease.close() // idempotent: does not release the other session's hold
        assertTrue(engine.isInUse)
        other.close()
        assertTrue(!engine.isInUse)
    }

    @Test fun `a failed load can be retried`() {
        val backend = FakeBackend().apply { failLoad = true }
        val engine = engine(backend)
        assertEquals(WhisperException.Reason.MODEL_LOAD_FAILED, reasonOf { engine.run(base, baseFile) })
        backend.failLoad = false
        assertEquals(" เปิด YouTube เพลง Bodyslam", engine.run(base, baseFile).text)
        assertEquals(base.id, engine.loadedModelId)
    }

    @Test fun `inference marks come from the engine clock`() {
        var now = 0L
        val backend = FakeBackend().apply { duringTranscribe = { now += 450_000_000 } }
        val engine = WhisperEngine(backend, Dispatchers.IO) { now }
        val result = engine.run(base, baseFile)
        assertEquals(450L, result.processingMs)
        assertEquals(450_000_000L, result.endedAtNanos - result.startedAtNanos)
    }

    private fun reasonOf(block: () -> Unit): WhisperException.Reason {
        try {
            block()
        } catch (error: WhisperException) {
            return error.reason
        }
        fail("expected WhisperException")
        throw AssertionError()
    }
}
