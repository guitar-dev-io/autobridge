package dev.autobridge.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

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
        override fun abort(handle: Long) { events += "abort $handle" }
        override fun transcribe(handle: Long, pcm: FloatArray, language: String, translate: Boolean, threads: Int, prompt: String?): String? {
            check(handle in live) { "transcribe on freed handle" }
            events += "transcribe $handle $language"
            return if (failTranscribe) null else " เปิด YouTube เพลง Bodyslam"
        }
        override fun lastLanguage(handle: Long) = "th"
        override fun lastTimings(handle: Long) = floatArrayOf(300f, 120f, 1f, 0f)
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
            listOf("load ggml-base-q8_0.bin", "transcribe 1 auto", "free 1", "load ggml-tiny-q8_0.bin", "transcribe 2 auto"),
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
