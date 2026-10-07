package dev.autobridge.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class WhisperModelStoreTest {

    private lateinit var root: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val selection = object : ModelSelection {
        override var selectedModelId: String? = null
    }

    private val base = WhisperModelCatalog.find("base-q8_0")!!
    private val tiny = WhisperModelCatalog.find("tiny-q8_0")!!

    @Before fun setUp() {
        root = Files.createTempDirectory("whisper-store").toFile()
    }

    @After fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    // ------------------------------------------------------------------------------- fixtures

    /** A file with a real whisper header for [info] and random "weights" after it. */
    private fun modelBytes(info: WhisperModelInfo, payload: Int = 300_000, seed: Int = 1): ByteArray {
        val header = ByteBuffer.allocate(WhisperModelHeader.BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(WhisperModelHeader.MAGIC)
            putInt(WhisperModelCatalog.MULTILINGUAL_VOCAB)
            putInt(1500)
            putInt(info.family.audioState)
            putInt(8)
            putInt(info.family.audioLayers)
            putInt(448)
            putInt(info.family.audioState)
            putInt(8)
            putInt(info.family.audioLayers)
            putInt(80)
            // Quantised files carry the quantisation-format version x1000.
            putInt(if (info.quantization == WhisperQuantization.F16) 1 else 2000 + info.quantization.ftype)
        }.array()
        return header + Random(seed).nextBytes(payload)
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Serves [content]; honours Range unless [ignoreRange]; can drop the connection after [failAfter] bytes. */
    private class FakeHttp(
        var content: ByteArray,
        var sha256: String? = null,
        var code: Int = 200,
        var ignoreRange: Boolean = false,
        var failAfter: Long = -1
    ) : ModelHttp {
        val ranges = mutableListOf<Long>()

        override fun open(url: String, fromByte: Long): ModelHttpResponse {
            ranges += fromByte
            if (code !in 200..299) {
                return ModelHttpResponse(code, -1, -1, false, null, InputStream.nullInputStream()) {}
            }
            val partial = fromByte > 0 && !ignoreRange
            if (partial && fromByte >= content.size) {
                return ModelHttpResponse(416, -1, -1, false, null, InputStream.nullInputStream()) {}
            }
            val start = if (partial) fromByte.toInt() else 0
            val body = content.copyOfRange(start, content.size)
            val limit = failAfter
            val stream: InputStream = if (limit < 0) ByteArrayInputStream(body) else object : InputStream() {
                val inner = ByteArrayInputStream(body)
                var served = 0L
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (served >= limit) throw IOException("connection reset")
                    val n = inner.read(b, off, minOf(len.toLong(), limit - served).toInt())
                    if (n > 0) served += n
                    return n
                }
            }
            return ModelHttpResponse(
                code = if (partial) 206 else 200,
                contentLength = body.size.toLong(),
                totalLength = content.size.toLong(),
                partial = partial,
                sha256 = sha256,
                body = stream
            ) {}
        }
    }

    private fun store(http: ModelHttp, free: Long = Long.MAX_VALUE) =
        WhisperModelStore(root, selection, http, scope, freeBytes = { free })

    // ------------------------------------------------------------------------------- listing

    @Test fun `available list is the catalog`() {
        val store = store(FakeHttp(ByteArray(0)))
        assertEquals(WhisperModelCatalog.models, store.listAvailableModels())
        assertTrue(store.getInstalledModels().isEmpty())
        assertNull(store.getSelectedModel())
    }

    @Test fun `missing model is not installed and has no path`() {
        val store = store(FakeHttp(ByteArray(0)))
        assertFalse(store.isModelInstalled(base.id))
        assertNull(store.getModelPath(base.id))
        assertEquals(ModelVerification.MISSING, store.verifyModel(base.id))
        assertFalse(store.isModelInstalled("no-such-model"))
    }

    // ------------------------------------------------------------------------------ download

    @Test fun `download installs atomically, records its checksum and becomes active`() {
        val bytes = modelBytes(base)
        val store = store(FakeHttp(bytes, sha256 = sha256(bytes)))
        store.downloadBlocking(base)

        assertTrue(store.isModelInstalled(base.id))
        val file = store.getModelPath(base.id)!!
        assertEquals("ggml-base-q8_0.bin", file.name)
        assertTrue(file.readBytes().contentEquals(bytes))
        assertFalse(File(root, file.name + WhisperModelStore.PART_SUFFIX).exists())
        assertEquals(sha256(bytes), File(root, file.name + WhisperModelStore.SHA256_SUFFIX).readText())
        assertEquals(base.id, store.getSelectedModel()?.id)
        assertEquals(ModelVerification.OK, store.verifyModel(base.id))
    }

    @Test fun `a second model does not take over the active one`() {
        val http = FakeHttp(modelBytes(base))
        val store = store(http)
        store.downloadBlocking(base)
        http.content = modelBytes(tiny)
        store.downloadBlocking(tiny)
        assertEquals(base.id, store.getSelectedModel()?.id)
        assertEquals(listOf(tiny.id, base.id), store.getInstalledModels().map { it.id })
    }

    @Test fun `downloading an installed model is a no-op, not a duplicate`() {
        val http = FakeHttp(modelBytes(base))
        val store = store(http)
        store.downloadBlocking(base)
        store.downloadBlocking(base)
        assertEquals(1, http.ranges.size)
        assertEquals(2, root.listFiles()!!.size) // the model and its .sha256
    }

    @Test fun `checksum mismatch discards the file`() {
        val bytes = modelBytes(base)
        val store = store(FakeHttp(bytes, sha256 = "0".repeat(64)))
        val error = expectFailure { store.downloadBlocking(base) }
        assertEquals(ModelError.CHECKSUM_MISMATCH, error)
        assertFalse(store.isModelInstalled(base.id))
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun `published sha1 is checked for full-precision models`() {
        val tinyF16 = WhisperModelCatalog.find("tiny")!!
        val store = store(FakeHttp(modelBytes(tinyF16)))
        // Our synthetic bytes cannot match whisper.cpp's published SHA-1.
        assertEquals(ModelError.CHECKSUM_MISMATCH, expectFailure { store.downloadBlocking(tinyF16) })
        assertFalse(store.isModelInstalled(tinyF16.id))
    }

    @Test fun `an HTML error page saved as the model is rejected as corrupted`() {
        val page = "<html><body>Rate limited</body></html>".toByteArray() + ByteArray(100)
        val store = store(FakeHttp(page))
        assertEquals(ModelError.CORRUPTED, expectFailure { store.downloadBlocking(base) })
        assertFalse(store.isModelInstalled(base.id))
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun `a file of another size class is rejected`() {
        val store = store(FakeHttp(modelBytes(tiny)))
        assertEquals(ModelError.CORRUPTED, expectFailure { store.downloadBlocking(base) })
    }

    @Test fun `http error is reported and nothing is installed`() {
        val store = store(FakeHttp(ByteArray(0), code = 404))
        assertEquals(ModelError.HTTP, expectFailure { store.downloadBlocking(base) })
        assertFalse(store.isModelInstalled(base.id))
    }

    @Test fun `network failure keeps the partial file and retry resumes it`() {
        val bytes = modelBytes(base)
        val http = FakeHttp(bytes, sha256 = sha256(bytes), failAfter = 100_000)
        val store = store(http)
        assertEquals(ModelError.NETWORK, expectFailure { store.downloadBlocking(base) })
        assertFalse(store.isModelInstalled(base.id))
        val partial = store.partialBytes(base.id)
        assertTrue("partial kept: $partial", partial in 1..100_000)

        http.failAfter = -1
        store.downloadBlocking(base)
        assertEquals(listOf(0L, partial), http.ranges)
        assertTrue(store.isModelInstalled(base.id))
        assertTrue(store.getModelPath(base.id)!!.readBytes().contentEquals(bytes))
        assertEquals(ModelVerification.OK, store.verifyModel(base.id))
    }

    @Test fun `a server that ignores Range gets a clean restart`() {
        val bytes = modelBytes(base)
        val http = FakeHttp(bytes, sha256 = sha256(bytes), failAfter = 50_000)
        val store = store(http)
        expectFailure { store.downloadBlocking(base) }
        http.failAfter = -1
        http.ignoreRange = true
        store.downloadBlocking(base)
        assertTrue(store.getModelPath(base.id)!!.readBytes().contentEquals(bytes))
    }

    @Test fun `cancel deletes the partial file`() {
        val store = store(FakeHttp(modelBytes(base)))
        val cancelled = AtomicBoolean(true)
        assertEquals(ModelError.CANCELLED, expectFailure { store.downloadBlocking(base, cancelled) })
        assertEquals(0L, store.partialBytes(base.id))
        assertFalse(store.isModelInstalled(base.id))
    }

    @Test fun `background download reports progress then finishes`() {
        val bytes = modelBytes(base, payload = 2_000_000)
        val store = store(FakeHttp(bytes, sha256 = sha256(bytes)))
        assertTrue(store.downloadModel(base.id))
        waitFor { store.isModelInstalled(base.id) && !store.isDownloading(base.id) }
        assertNull(store.downloads.value[base.id])
    }

    @Test fun `background failure is reported, then retry succeeds`() {
        val bytes = modelBytes(base)
        val http = FakeHttp(bytes, failAfter = 10_000)
        val store = store(http)
        store.downloadModel(base.id)
        waitFor { store.downloads.value[base.id] is DownloadState.Failed }
        assertEquals(ModelError.NETWORK, (store.downloads.value[base.id] as DownloadState.Failed).error)

        http.failAfter = -1
        assertTrue(store.downloadModel(base.id))
        waitFor { store.isModelInstalled(base.id) && !store.isDownloading(base.id) }
    }

    @Test fun `import-only models cannot be downloaded`() {
        val kQuant = WhisperModelCatalog.find("base-q5_k")!!
        val store = store(FakeHttp(ByteArray(0)))
        assertFalse(store.downloadModel(kQuant.id))
        assertEquals(ModelError.NOT_DOWNLOADABLE, (store.downloads.value[kQuant.id] as DownloadState.Failed).error)
    }

    @Test fun `insufficient storage is refused before downloading`() {
        val http = FakeHttp(modelBytes(base))
        val store = store(http, free = 10_000_000)
        assertEquals(ModelError.INSUFFICIENT_STORAGE, expectFailure { store.downloadBlocking(base) })
        assertTrue(http.ranges.isEmpty())
    }

    // ------------------------------------------------------------------- select and delete

    @Test fun `selection persists across store instances`() {
        val http = FakeHttp(modelBytes(base))
        val first = store(http)
        first.downloadBlocking(base)
        http.content = modelBytes(tiny)
        first.downloadBlocking(tiny)
        assertTrue(first.selectModel(tiny.id))

        // A new store over the same directory and preferences is what an app restart is.
        val restarted = store(FakeHttp(ByteArray(0)))
        assertEquals(tiny.id, restarted.getSelectedModel()?.id)
    }

    @Test fun `cannot select a model that is not installed`() {
        val store = store(FakeHttp(ByteArray(0)))
        assertFalse(store.selectModel(base.id))
        assertNull(selection.selectedModelId)
    }

    @Test fun `the active model cannot be deleted until another is selected`() {
        val http = FakeHttp(modelBytes(base))
        val store = store(http)
        store.downloadBlocking(base)
        assertEquals(ModelDeletion.ACTIVE_MODEL, store.deleteModel(base.id))
        assertTrue(store.isModelInstalled(base.id))

        http.content = modelBytes(tiny)
        store.downloadBlocking(tiny)
        store.selectModel(tiny.id)
        assertEquals(ModelDeletion.DELETED, store.deleteModel(base.id))
        assertFalse(store.isModelInstalled(base.id))
        assertEquals(ModelDeletion.NOT_FOUND, store.deleteModel(base.id))
        assertFalse(File(root, base.fileName + WhisperModelStore.SHA256_SUFFIX).exists())
    }

    @Test fun `a selected model whose file vanished is no longer selected`() {
        val store = store(FakeHttp(modelBytes(base)))
        store.downloadBlocking(base)
        store.getModelPath(base.id)!!.delete()
        assertNull(store.getSelectedModel())
    }

    // -------------------------------------------------------------------------- corruption

    @Test fun `a corrupted file on disk is not installed`() {
        val store = store(FakeHttp(modelBytes(base)))
        store.downloadBlocking(base)
        val file = store.getModelPath(base.id)!!
        file.writeBytes(ByteArray(16))
        assertFalse(store.isModelInstalled(base.id))
        assertEquals(ModelVerification.CORRUPTED, store.verifyModel(base.id))
    }

    @Test fun `bit rot after install is caught by verify`() {
        val store = store(FakeHttp(modelBytes(base)))
        store.downloadBlocking(base)
        val file = store.getModelPath(base.id)!!
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
        file.writeBytes(bytes)
        assertTrue(store.isModelInstalled(base.id)) // the header is still fine...
        assertEquals(ModelVerification.CHECKSUM_MISMATCH, store.verifyModel(base.id)) // ...the content is not
    }

    @Test fun `a partial file is never reported as installed`() {
        val bytes = modelBytes(base)
        File(root, base.fileName + WhisperModelStore.PART_SUFFIX).apply { parentFile.mkdirs() }.writeBytes(bytes)
        val store = store(FakeHttp(bytes))
        assertFalse(store.isModelInstalled(base.id))
        assertTrue(store.getInstalledModels().isEmpty())
    }

    // ------------------------------------------------------------------------------ import

    @Test fun `import recognises the model from its header`() {
        val kQuant = WhisperModelCatalog.find("base-q5_k")!!
        val store = store(FakeHttp(ByteArray(0)))
        val imported = store.importModel(ByteArrayInputStream(modelBytes(kQuant)))
        assertEquals(kQuant.id, imported.id)
        assertTrue(store.isModelInstalled(kQuant.id))
        assertEquals(ModelVerification.OK, store.verifyModel(kQuant.id))
    }

    @Test fun `import refuses a file that is not a model`() {
        val store = store(FakeHttp(ByteArray(0)))
        assertEquals(ModelError.NOT_RECOGNIZED, expectFailure { store.importModel(ByteArrayInputStream(ByteArray(1000))) })
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun `import refuses an english-only model`() {
        val bytes = modelBytes(base)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 51864)
        val store = store(FakeHttp(ByteArray(0)))
        assertEquals(ModelError.NOT_RECOGNIZED, expectFailure { store.importModel(ByteArrayInputStream(bytes)) })
    }

    @Test fun `import of an installed model is refused, not duplicated`() {
        val store = store(FakeHttp(modelBytes(base)))
        store.downloadBlocking(base)
        assertEquals(ModelError.ALREADY_INSTALLED, expectFailure { store.importModel(ByteArrayInputStream(modelBytes(base, seed = 2))) })
        assertEquals(2, root.listFiles()!!.size)
    }

    // ----------------------------------------------------------------------------- helpers

    private fun expectFailure(block: () -> Unit): ModelError {
        try {
            block()
        } catch (error: ModelException) {
            return error.error
        }
        fail("expected a ModelException")
        throw AssertionError()
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out")
            Thread.sleep(10)
        }
        assertNotNull(Unit)
    }
}
