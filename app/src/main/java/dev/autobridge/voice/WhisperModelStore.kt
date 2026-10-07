package dev.autobridge.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Where the chosen model id is kept; the app backs it with [VoiceSettings]. */
interface ModelSelection {
    var selectedModelId: String?
}

/** One HTTP response body, already past any redirects. */
class ModelHttpResponse(
    val code: Int,
    /** Bytes in this body, or -1 when the server did not say. */
    val contentLength: Long,
    /** The whole file's size: Content-Range's total for a partial response, else Content-Length. */
    val totalLength: Long,
    /** True for 206: the body continues from the requested offset. */
    val partial: Boolean,
    /** The SHA-256 the server publishes for the file (Hugging Face's X-Linked-ETag), if any. */
    val sha256: String?,
    val body: InputStream,
    private val onClose: () -> Unit
) : AutoCloseable {
    override fun close() {
        runCatching { body.close() }
        onClose()
    }
}

/** Opens a GET for [url] starting at [fromByte]. Swappable so the store is tested without a network. */
fun interface ModelHttp {
    @Throws(IOException::class)
    fun open(url: String, fromByte: Long): ModelHttpResponse
}

/** Why a model download or import did not produce an installed model. */
enum class ModelError {
    NETWORK,
    HTTP,
    INSUFFICIENT_STORAGE,
    INCOMPLETE,
    CHECKSUM_MISMATCH,
    CORRUPTED,
    NOT_DOWNLOADABLE,
    NOT_RECOGNIZED,
    ALREADY_INSTALLED,
    CANCELLED
}

class ModelException(val error: ModelError, message: String) : IOException(message)

/** What the settings screen shows for one model while it is, or is not, being fetched. */
sealed interface DownloadState {
    data object Idle : DownloadState
    data class Running(val bytes: Long, val total: Long) : DownloadState {
        val percent: Int get() = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else 0
    }
    data object Verifying : DownloadState
    data class Failed(val error: ModelError, val detail: String) : DownloadState
}

/** Result of [WhisperModelStore.verifyModel]. */
enum class ModelVerification { OK, MISSING, CORRUPTED, CHECKSUM_MISMATCH }

/** Result of [WhisperModelStore.deleteModel]. */
enum class ModelDeletion { DELETED, NOT_FOUND, ACTIVE_MODEL }

/**
 * Downloads, verifies, imports, lists, selects and deletes the Whisper models on this device.
 *
 * Follows the convention [dev.autobridge.subtitles.opusmt.OpusMtModelStore] set for downloaded
 * models - app-private files directory, nothing removed except on request - and adds what a single
 * file of up to a few hundred megabytes needs on top:
 *
 * - **Never a half model.** Bytes go to `<file>.part`; only a file that arrived whole, matches
 *   the size and SHA-256 the server announced (and whisper.cpp's published SHA-1 where it has
 *   one), and has a header that says it is the model it was downloaded as, is renamed - in the
 *   same directory, so atomically - to its final name. Nothing else ever counts as installed.
 * - **Retry resumes.** A network failure keeps the `.part`; the next attempt asks for the rest with
 *   a Range request. Cancel deletes it, because cancelling is the user saying they want the space.
 * - **Space first.** Free space is checked before the first byte and against the server's size,
 *   with a margin, so a download does not fill the phone and fail at 97%.
 * - **One copy.** A model has one file name; downloading or importing one that is installed is a
 *   no-op, never a duplicate.
 *
 * Downloads run on this store's own scope, not a screen's, so leaving the settings screen does not
 * abandon one; [downloads] is what a screen watches when it comes back.
 */
class WhisperModelStore(
    val root: File,
    private val selection: ModelSelection,
    private val http: ModelHttp = UrlConnectionModelHttp(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val catalog: List<WhisperModelInfo> = WhisperModelCatalog.models,
    private val log: (String) -> Unit = {},
    /** Free bytes where models are stored; swappable so the out-of-space path can be tested. */
    private val freeBytes: (File) -> Long = { it.usableSpace }
) {
    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())

    /** Per-model download state; absent means idle. */
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads.asStateFlow()

    private val _revision = MutableStateFlow(0)

    /** Bumped whenever the installed set or the selection changes. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val running = ConcurrentHashMap<String, Running>()
    private val fileLock = Any()

    private class Running(val cancelled: AtomicBoolean = AtomicBoolean(false)) {
        var job: Job? = null
    }

    // ------------------------------------------------------------------------------- listing

    fun listAvailableModels(): List<WhisperModelInfo> = catalog

    fun info(id: String): WhisperModelInfo? = catalog.firstOrNull { it.id == id }

    fun modelFile(info: WhisperModelInfo): File = File(root, info.fileName)

    private fun partFile(info: WhisperModelInfo) = File(root, info.fileName + PART_SUFFIX)

    private fun checksumFile(info: WhisperModelInfo) = File(root, info.fileName + SHA256_SUFFIX)

    /** Installed means: the final file exists and its header is the model it is filed as. */
    fun isModelInstalled(id: String): Boolean {
        val info = info(id) ?: return false
        val file = modelFile(info)
        return file.isFile && WhisperModelHeader.problemFor(info, WhisperModelHeader.read(file)) == null
    }

    fun getInstalledModels(): List<WhisperModelInfo> = catalog.filter { isModelInstalled(it.id) }

    fun getModelPath(id: String): File? = info(id)?.takeIf { isModelInstalled(id) }?.let(::modelFile)

    /** Bytes used by installed models (partial downloads excluded). */
    fun installedBytes(): Long = getInstalledModels().sumOf { modelFile(it).length() }

    /** Bytes held by interrupted downloads waiting to be resumed. */
    fun partialBytes(id: String): Long = info(id)?.let { partFile(it).takeIf(File::isFile)?.length() } ?: 0L

    // ----------------------------------------------------------------------------- selection

    /** The selected model, or null when none is selected or the selected file is gone. */
    fun getSelectedModel(): WhisperModelInfo? =
        selection.selectedModelId?.let(::info)?.takeIf { isModelInstalled(it.id) }

    /** Makes [id] the active model. False when it is not installed. */
    fun selectModel(id: String): Boolean {
        if (!isModelInstalled(id)) return false
        if (selection.selectedModelId != id) {
            selection.selectedModelId = id
            log("whisper model selected: $id")
        }
        _revision.update { it + 1 }
        return true
    }

    // -------------------------------------------------------------------------------- delete

    /**
     * Removes [id] and anything left of a download of it. The active model is refused: something
     * else has to be selected first, so voice commands never silently lose their model.
     */
    fun deleteModel(id: String): ModelDeletion {
        val info = info(id) ?: return ModelDeletion.NOT_FOUND
        if (selection.selectedModelId == id && isModelInstalled(id)) return ModelDeletion.ACTIVE_MODEL
        cancelDownload(id)
        val removed = synchronized(fileLock) {
            val a = modelFile(info).delete()
            val b = partFile(info).delete()
            checksumFile(info).delete()
            a || b
        }
        if (selection.selectedModelId == id) selection.selectedModelId = null
        _revision.update { it + 1 }
        if (removed) log("whisper model deleted: $id")
        return if (removed) ModelDeletion.DELETED else ModelDeletion.NOT_FOUND
    }

    // ------------------------------------------------------------------------------ download

    /**
     * Starts fetching [id] in the background. Returns false when it cannot start: unknown, not
     * downloadable, or already downloading. Progress and the outcome land in [downloads].
     */
    fun downloadModel(id: String): Boolean {
        val info = info(id) ?: return false
        if (!info.downloadable) {
            setState(id, DownloadState.Failed(ModelError.NOT_DOWNLOADABLE, info.id))
            return false
        }
        val handle = Running()
        if (running.putIfAbsent(id, handle) != null) return false
        setState(id, DownloadState.Running(partialBytes(id), info.sizeBytes))
        handle.job = scope.launch {
            val outcome: DownloadState? = try {
                downloadBlocking(info, handle.cancelled)
                null
            } catch (error: ModelException) {
                if (error.error == ModelError.CANCELLED) null
                else DownloadState.Failed(error.error, error.message.orEmpty())
            } catch (error: Throwable) {
                DownloadState.Failed(ModelError.NETWORK, error.message ?: error.javaClass.simpleName)
            } finally {
                // Unregistered before the outcome is published, so a Retry tapped the moment
                // "failed" appears is never refused as "already downloading".
                running.remove(id, handle)
            }
            setState(id, outcome)
        }
        return true
    }

    /** Stops a download of [id] and deletes what it fetched. No-op when none is running. */
    fun cancelDownload(id: String) {
        val handle = running[id] ?: return
        handle.cancelled.set(true)
        log("whisper download cancelled: $id")
    }

    fun isDownloading(id: String): Boolean = running.containsKey(id)

    /**
     * The download itself, on the calling thread. Public for tests; the app calls [downloadModel].
     */
    @Throws(ModelException::class)
    fun downloadBlocking(info: WhisperModelInfo, cancelled: AtomicBoolean = AtomicBoolean(false)) {
        val url = info.downloadUrl ?: throw ModelException(ModelError.NOT_DOWNLOADABLE, info.id)
        if (isModelInstalled(info.id)) return
        if (!root.isDirectory && !root.mkdirs()) {
            throw ModelException(ModelError.INSUFFICIENT_STORAGE, "cannot create ${root.path}")
        }
        val part = partFile(info)
        try {
            fetch(info, url, part, cancelled, allowRestart = true)
        } catch (error: ModelException) {
            when (error.error) {
                // Kept for a resume: the bytes so far are good, the connection was not.
                ModelError.NETWORK, ModelError.HTTP -> Unit
                else -> part.delete()
            }
            log("whisper download ${info.id} failed: ${error.error} ${error.message}")
            throw error
        }
    }

    private fun fetch(
        info: WhisperModelInfo,
        url: String,
        part: File,
        cancelled: AtomicBoolean,
        allowRestart: Boolean
    ) {
        var existing = if (part.isFile) part.length() else 0L
        requireSpace(info.sizeBytes - existing)

        val response = try {
            http.open(url, existing)
        } catch (error: IOException) {
            throw ModelException(ModelError.NETWORK, error.message ?: error.javaClass.simpleName)
        }
        response.use { body ->
            if (body.code == HTTP_RANGE_NOT_SATISFIABLE && existing > 0 && allowRestart) {
                // The .part is as long as, or longer than, the file: start over.
                part.delete()
                body.close()
                fetch(info, url, part, cancelled, allowRestart = false)
                return
            }
            if (body.code !in 200..299) throw ModelException(ModelError.HTTP, "HTTP ${body.code}")
            if (existing > 0 && !body.partial) {
                // The server ignored the Range request and is sending everything again.
                part.delete()
                existing = 0
            }
            val total = body.totalLength.takeIf { it > 0 }
            total?.let { requireSpace(it - existing) }

            val sha256 = MessageDigest.getInstance("SHA-256")
            val sha1 = info.sha1?.let { MessageDigest.getInstance("SHA-1") }
            if (existing > 0) digestFile(part, listOfNotNull(sha256, sha1))

            setState(info.id, DownloadState.Running(existing, total ?: info.sizeBytes))
            var written = existing
            try {
                FileOutputStream(part, existing > 0).use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var lastReport = 0L
                    while (true) {
                        if (cancelled.get()) throw ModelException(ModelError.CANCELLED, "cancelled")
                        val count = body.body.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                        sha256.update(buffer, 0, count)
                        sha1?.update(buffer, 0, count)
                        written += count
                        if (written - lastReport >= REPORT_EVERY_BYTES) {
                            lastReport = written
                            setState(info.id, DownloadState.Running(written, total ?: info.sizeBytes))
                        }
                    }
                    out.fd.sync()
                }
            } catch (error: ModelException) {
                throw error
            } catch (error: IOException) {
                if (cancelled.get()) throw ModelException(ModelError.CANCELLED, "cancelled")
                if (freeBytes(root) < MIN_FREE_BYTES) {
                    throw ModelException(ModelError.INSUFFICIENT_STORAGE, error.message.orEmpty())
                }
                throw ModelException(ModelError.NETWORK, error.message ?: error.javaClass.simpleName)
            }

            setState(info.id, DownloadState.Verifying)
            if (total != null && written < total) {
                // The connection ended early without an error: keep what arrived and let a retry resume.
                throw ModelException(ModelError.NETWORK, "connection closed at $written of $total bytes")
            }
            if (total != null && written > total) {
                throw ModelException(ModelError.INCOMPLETE, "received $written bytes, expected $total")
            }
            val actualSha256 = sha256.digest().toHex()
            body.sha256?.let { expected ->
                if (!expected.equals(actualSha256, ignoreCase = true)) {
                    throw ModelException(ModelError.CHECKSUM_MISMATCH, "sha256 $actualSha256 != $expected")
                }
            }
            if (sha1 != null) {
                val actual = sha1.digest().toHex()
                if (!actual.equals(info.sha1, ignoreCase = true)) {
                    throw ModelException(ModelError.CHECKSUM_MISMATCH, "sha1 $actual != ${info.sha1}")
                }
            }
            install(info, part, actualSha256)
        }
    }

    // -------------------------------------------------------------------------------- import

    /**
     * Copies a model file the user picked into place. The file says what it is: its header is
     * matched against the catalog, so a renamed or unrelated file is refused rather than filed
     * under the wrong name. Blocking; returns the model it was recognised as.
     */
    @Throws(ModelException::class)
    fun importModel(input: InputStream, sizeHint: Long = -1): WhisperModelInfo {
        if (!root.isDirectory && !root.mkdirs()) {
            throw ModelException(ModelError.INSUFFICIENT_STORAGE, "cannot create ${root.path}")
        }
        if (sizeHint > 0) requireSpace(sizeHint)
        val temp = File(root, "import$PART_SUFFIX")
        val sha256 = MessageDigest.getInstance("SHA-256")
        try {
            input.use { source ->
                FileOutputStream(temp).use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                        sha256.update(buffer, 0, count)
                    }
                    out.fd.sync()
                }
            }
            val info = WhisperModelHeader.read(temp)?.let(::matchCatalog)
                ?: throw ModelException(ModelError.NOT_RECOGNIZED, "not a multilingual whisper model this app lists")
            if (isModelInstalled(info.id)) throw ModelException(ModelError.ALREADY_INSTALLED, info.id)
            install(info, temp, sha256.digest().toHex())
            return info
        } catch (error: ModelException) {
            temp.delete()
            throw error
        } catch (error: IOException) {
            temp.delete()
            throw ModelException(
                if (freeBytes(root) < MIN_FREE_BYTES) ModelError.INSUFFICIENT_STORAGE else ModelError.CORRUPTED,
                error.message ?: error.javaClass.simpleName
            )
        }
    }

    private fun matchCatalog(header: WhisperModelHeader): WhisperModelInfo? =
        catalog.firstOrNull { WhisperModelHeader.problemFor(it, header) == null }

    // -------------------------------------------------------------------------------- verify

    /**
     * Full check of an installed model: header, and the file's SHA-256 against the one recorded when
     * it was installed (and the published SHA-1, where there is one). Reads the whole file, so it
     * runs off the main thread; it is what the settings screen offers when a model will not load.
     */
    fun verifyModel(id: String): ModelVerification {
        val info = info(id) ?: return ModelVerification.MISSING
        val file = modelFile(info)
        if (!file.isFile) return ModelVerification.MISSING
        if (WhisperModelHeader.problemFor(info, WhisperModelHeader.read(file)) != null) {
            return ModelVerification.CORRUPTED
        }
        val sha256 = MessageDigest.getInstance("SHA-256")
        val sha1 = info.sha1?.let { MessageDigest.getInstance("SHA-1") }
        try {
            digestFile(file, listOfNotNull(sha256, sha1))
        } catch (_: IOException) {
            return ModelVerification.CORRUPTED
        }
        val recorded = checksumFile(info).takeIf(File::isFile)?.readText()?.trim()
        if (recorded != null && !recorded.equals(sha256.digest().toHex(), ignoreCase = true)) {
            return ModelVerification.CHECKSUM_MISMATCH
        }
        if (sha1 != null && !sha1.digest().toHex().equals(info.sha1, ignoreCase = true)) {
            return ModelVerification.CHECKSUM_MISMATCH
        }
        return ModelVerification.OK
    }

    // ------------------------------------------------------------------------------ internals

    private fun install(info: WhisperModelInfo, staged: File, sha256: String) {
        val problem = WhisperModelHeader.problemFor(info, WhisperModelHeader.read(staged))
        if (problem != null) {
            staged.delete()
            throw ModelException(ModelError.CORRUPTED, "header: $problem")
        }
        synchronized(fileLock) {
            val target = modelFile(info)
            if (!staged.renameTo(target)) {
                staged.delete()
                throw ModelException(ModelError.CORRUPTED, "could not move into place")
            }
            runCatching { checksumFile(info).writeText(sha256) }
        }
        log("whisper model installed: ${info.id} (${modelFile(info).length()} bytes)")
        // The first model installed becomes the active one; later ones wait to be chosen.
        if (getSelectedModel() == null) selection.selectedModelId = info.id
        _revision.update { it + 1 }
    }

    private fun requireSpace(bytes: Long) {
        val needed = maxOf(0L, bytes) + MIN_FREE_BYTES
        // 0 is what File.usableSpace answers when it cannot tell; that is not "full".
        val free = freeBytes(root)
        if (free in 1 until needed) {
            throw ModelException(ModelError.INSUFFICIENT_STORAGE, "needs $needed bytes, $free free")
        }
    }

    private fun setState(id: String, state: DownloadState?) {
        _downloads.update { current ->
            if (state == null) current - id else current + (id to state)
        }
    }

    /** Clears a Failed state once the screen has shown it. */
    fun clearFailure(id: String) {
        if (_downloads.value[id] is DownloadState.Failed) setState(id, null)
    }

    companion object {
        const val PART_SUFFIX = ".part"
        const val SHA256_SUFFIX = ".sha256"
        private const val BUFFER_BYTES = 128 * 1024
        private const val REPORT_EVERY_BYTES = 512L * 1024
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416

        /** Headroom left on the device after a model lands, so the phone itself is not starved. */
        const val MIN_FREE_BYTES = 64L * 1024 * 1024

        private fun digestFile(file: File, digests: List<MessageDigest>) {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digests.forEach { it.update(buffer, 0, count) }
                }
            }
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}

/**
 * [ModelHttp] over [HttpURLConnection], following redirects itself so the checksum header on the
 * first hop is not lost: Hugging Face answers `resolve/main/...` with a redirect to its CDN and
 * puts the file's SHA-256 (X-Linked-ETag) and size (X-Linked-Size) on that redirect.
 */
class UrlConnectionModelHttp(
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 60_000
) : ModelHttp {

    override fun open(url: String, fromByte: Long): ModelHttpResponse {
        var current = URL(url)
        var linkedSha256: String? = null
        repeat(MAX_REDIRECTS) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "AutoBridge")
                if (fromByte > 0) setRequestProperty("Range", "bytes=$fromByte-")
            }
            val code = try {
                connection.responseCode
            } catch (error: InterruptedIOException) {
                connection.disconnect()
                throw error
            }
            linkedSha256 = linkedSha256 ?: sha256From(connection.getHeaderField("X-Linked-ETag"))
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) throw IOException("redirect without Location")
                val next = URL(current, location)
                // Never follow a download off HTTPS.
                if (next.protocol != "https") throw IOException("refusing non-HTTPS redirect")
                current = next
                return@repeat
            }
            val partial = code == HttpURLConnection.HTTP_PARTIAL
            val length = connection.contentLengthLong
            val total = if (partial) {
                connection.getHeaderField("Content-Range")
                    ?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: -1L
            } else length
            val body = if (code in 200..299) connection.inputStream else (connection.errorStream ?: ByteArrayInputStream(ByteArray(0)))
            return ModelHttpResponse(
                code = code,
                contentLength = length,
                totalLength = total,
                partial = partial,
                sha256 = linkedSha256 ?: sha256From(connection.getHeaderField("ETag")),
                body = body,
                onClose = { connection.disconnect() }
            )
        }
        throw IOException("too many redirects for $url")
    }

    private fun sha256From(header: String?): String? =
        header?.trim()?.removePrefix("W/")?.trim('"')?.lowercase()?.takeIf { SHA256.matches(it) }

    private companion object {
        const val MAX_REDIRECTS = 6
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}
