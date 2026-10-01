package dev.autobridge.subtitles.opusmt

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.autobridge.display.StructuredLog
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads, lists and deletes the Opus-MT models on this device.
 *
 * The files live under the app's own files directory rather than its cache directory, which is the
 * one deliberate departure from calling this a cache: the system empties a cache directory under
 * storage pressure, and losing eighty megabytes someone chose to download - possibly on a
 * tethered connection, in a car - because a photo app wanted the space is not a trade this gets to
 * make on their behalf. Nothing here is removed except on request, which is what makes the
 * cleanup row on the settings screen necessary rather than decorative.
 *
 * Every call blocks. The settings screen and the translator both run these off the main thread.
 */
object OpusMtModelStore {
    /** How much of a file to move per read; large enough that an 80 MB graph is not a million calls. */
    private const val BUFFER_BYTES = 128 * 1024
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 60_000

    /** Progress for one model download, as whole files plus the bytes of the one in flight. */
    data class Progress(
        val file: OpusMtFile,
        val fileIndex: Int,
        val fileCount: Int,
        val bytesRead: Long,
        val bytesTotal: Long
    ) {
        /** 0..100 across the whole model, with each file weighted equally when sizes are unknown. */
        val percent: Int
            get() {
                val withinFile = if (bytesTotal > 0) bytesRead.toDouble() / bytesTotal else 0.0
                return (((fileIndex + withinFile) / fileCount) * 100).toInt().coerceIn(0, 100)
            }
    }

    /** A model already on disk. */
    data class Installed(val id: String, val bytes: Long) {
        val model: OpusMtModel? get() = OpusMtCatalog.model(id)
        val label: String get() = model?.label ?: id
    }

    fun root(context: Context): File =
        File(context.applicationContext.filesDir, OpusMtModelLayout.ROOT_DIRECTORY)

    fun directory(context: Context, model: OpusMtModel): File = File(root(context), model.id)

    fun isInstalled(context: Context, model: OpusMtModel): Boolean {
        val dir = directory(context, model)
        if (!dir.isDirectory) return false
        return OpusMtModelLayout.isComplete(dir.list()?.toList() ?: emptyList())
    }

    /** Every complete model on disk, largest first - the order a cleanup screen is read in. */
    fun installed(context: Context): List<Installed> {
        val directories = root(context).listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return directories
            .filterNot { OpusMtModelLayout.isPartial(it.name) }
            .filter { OpusMtModelLayout.isComplete(it.list()?.toList() ?: emptyList()) }
            .map { Installed(it.name, sizeOf(it)) }
            .sortedByDescending { it.bytes }
    }

    fun totalBytes(context: Context): Long = sizeOf(root(context))

    /**
     * Removes one model, complete or half-downloaded. Returns true when something was deleted.
     */
    fun delete(context: Context, id: String): Boolean {
        val target = File(root(context), id)
        val partial = File(root(context), id + OpusMtModelLayout.PARTIAL_SUFFIX)
        val removed = target.deleteRecursively() or partial.deleteRecursively()
        if (removed) StructuredLog.i("SUBTITLE", "opus-mt model removed: $id")
        return removed
    }

    /**
     * Deletes everything except [keep], and returns how many bytes that freed.
     *
     * This is what the "Free up space" row runs. The sizes are taken before the delete, because
     * afterwards there is nothing left to measure.
     */
    fun deleteOthers(context: Context, keep: Set<String>): Long {
        val directories = root(context).listFiles()?.filter { it.isDirectory } ?: return 0L
        val plan = OpusMtModelLayout.cleanupPlan(directories.map { it.name }, keep)
        var freed = 0L
        for (name in plan) {
            val directory = File(root(context), name)
            val size = sizeOf(directory)
            if (directory.deleteRecursively()) freed += size
        }
        if (freed > 0L) {
            StructuredLog.i(
                "SUBTITLE",
                "opus-mt cleanup removed ${plan.size} model(s), ${OpusMtModelLayout.formatSize(freed)}"
            )
        }
        return freed
    }

    /**
     * Fetches every file of [model] into place, reporting progress as it goes.
     *
     * The download goes into a `.partial` directory that is renamed only once every file has
     * arrived, so an interrupted download can never be mistaken for an installed model. An
     * already-installed model returns immediately.
     *
     * Throws [FileNotFoundException] when the pair has no published export - the common outcome
     * for an [OpusMtAvailability.UNVERIFIED] pair, and the one message worth passing through
     * unchanged, because "this pair does not exist upstream" is not something a retry fixes.
     */
    @Throws(IOException::class)
    fun download(context: Context, model: OpusMtModel, onProgress: (Progress) -> Unit = {}) {
        if (isInstalled(context, model)) return
        val staging = File(root(context), model.id + OpusMtModelLayout.PARTIAL_SUFFIX)
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("Could not create ${staging.absolutePath}")

        try {
            OpusMtFile.all.forEachIndexed { index, file ->
                fetch(
                    url = OpusMtCatalog.downloadUrl(model, file),
                    target = File(staging, file.fileName),
                    onProgress = { read, total ->
                        onProgress(Progress(file, index, OpusMtFile.all.size, read, total))
                    }
                )
            }
            val destination = directory(context, model)
            destination.deleteRecursively()
            if (!staging.renameTo(destination)) {
                throw IOException("Could not install ${model.id}")
            }
            StructuredLog.i(
                "SUBTITLE",
                "opus-mt model installed: ${model.id} (${OpusMtModelLayout.formatSize(sizeOf(destination))})"
            )
        } catch (error: IOException) {
            staging.deleteRecursively()
            StructuredLog.w("SUBTITLE", "opus-mt download failed for ${model.id}: ${error.message}")
            throw error
        }
    }

    /**
     * True when the network policy allows a download now.
     *
     * The default is Wi-Fi only, and a car is exactly where that matters: the phone is usually on
     * mobile data, and eighty megabytes of translation model is not something to spend someone's
     * allowance on without being told to.
     */
    fun isDownloadAllowed(context: Context, wifiOnly: Boolean): Boolean {
        val manager = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (!wifiOnly) return true
        // Metered rather than "is it Wi-Fi": a metered hotspot is the case the setting exists for,
        // and an unmetered Ethernet dock in a workshop is not something to refuse.
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun fetch(url: String, target: File, onProgress: (Long, Long) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                throw FileNotFoundException("No published Opus-MT export at $url")
            }
            if (status !in 200..299) {
                throw IOException("HTTP $status for $url")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var read = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        read += count
                        onProgress(read, total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sizeOf(file: File): Long = when {
        !file.exists() -> 0L
        file.isFile -> file.length()
        else -> file.listFiles()?.sumOf { sizeOf(it) } ?: 0L
    }
}
