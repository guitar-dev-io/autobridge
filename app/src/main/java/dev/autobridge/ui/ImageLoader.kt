package dev.autobridge.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import dev.autobridge.R

/**
 * Small async image loader for channel logos and cover art.
 *
 * The project has no image-loading dependency and this needs to stay that way, so this is a
 * deliberately minimal one: a memory [LruCache] in front of a disk cache under `cacheDir`, a
 * bounded thread pool, and inSampleSize decoding so a provider's 1000px logo does not land in a
 * 46dp slot at full size.
 *
 * Views are recycled by the screens that use it, so every request tags its [ImageView] and a
 * finished decode is only applied when the tag still matches — otherwise a slow logo would land on
 * whatever row scrolled into its place.
 */
object ImageLoader {
    private const val MAX_DIMENSION = 256
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val CACHE_DIRECTORY = "image-cache"
    private const val MAX_DISK_ENTRIES = 400

    private val memory = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val worker = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "image-loader").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /**
     * Loads [url] into [target], calling [onLoaded] on the main thread once a bitmap is applied.
     * A cached hit is applied synchronously; a failure simply never calls back, which leaves the
     * caller's placeholder in place.
     */
    fun load(context: Context, url: String, target: ImageView, onLoaded: () -> Unit) {
        val address = url.trim()
        if (address.isEmpty() || address in failed) return
        target.setTag(R.id.autobridge_image_tag, address)

        memory.get(address)?.let { cached ->
            target.setImageBitmap(cached)
            onLoaded()
            return
        }

        val applicationContext = context.applicationContext
        worker.execute {
            val bitmap = runCatching { fetch(applicationContext, address) }.getOrNull()
            if (bitmap == null) {
                // Remember the failure so a list of dead logos is not retried on every scroll.
                failed += address
                return@execute
            }
            memory.put(address, bitmap)
            main.post {
                if (target.getTag(R.id.autobridge_image_tag) == address) {
                    target.setImageBitmap(bitmap)
                    onLoaded()
                }
            }
        }
    }

    /**
     * The same load for a caller that paints on a Canvas instead of owning an [ImageView].
     *
     * A cached bitmap is returned straight away; otherwise the fetch is started and null comes
     * back, and [onReady] is posted to the main thread once there is something new to draw. There
     * is no target view to tag here, so an in-flight set does the de-duplication that the view tag
     * does on [load] - without it a surface that re-renders on every scroll pixel would queue one
     * download per frame for the same URL.
     */
    fun bitmap(context: Context, url: String, onReady: () -> Unit): Bitmap? {
        val address = url.trim()
        if (address.isEmpty() || address in failed) return null
        memory.get(address)?.let { return it }
        if (!inFlight.add(address)) return null

        val applicationContext = context.applicationContext
        worker.execute {
            val bitmap = runCatching { fetch(applicationContext, address) }.getOrNull()
            inFlight.remove(address)
            if (bitmap == null) {
                failed += address
                return@execute
            }
            memory.put(address, bitmap)
            main.post(onReady)
        }
        return null
    }

    private fun fetch(context: Context, url: String): Bitmap? {
        val file = cacheFile(context, url)
        if (file.isFile && file.length() > 0) {
            decode(file.readBytes())?.let { return it }
            file.delete()
        }
        val bytes = download(url) ?: return null
        val bitmap = decode(bytes) ?: return null
        runCatching {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            trimDiskCache(file.parentFile)
        }
        return bitmap
    }

    private fun download(url: String): ByteArray? {
        val connection = (URL(url).openConnection() as? HttpURLConnection) ?: return null
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "AutoBridge/1.0 (Android)")
        try {
            if (connection.responseCode !in 200..299) return null
            val buffer = java.io.ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    buffer.write(chunk, 0, read)
                    if (buffer.size() > MAX_BYTES) return null
                }
            }
            return buffer.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    /** Two-pass decode: read the bounds, then sample down to roughly [MAX_DIMENSION]. */
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > MAX_DIMENSION || bounds.outHeight / sample > MAX_DIMENSION) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /**
     * Drops every cached logo, in memory and on disk, and clears the dead-URL set so previously
     * failed addresses are tried again. Returns the number of disk files removed.
     *
     * The disk directory itself is left in place; a later load recreates its contents. Safe to call
     * from any thread — [LruCache] and the failed set are already concurrent, and the file walk only
     * touches this loader's own cache subdirectory.
     */
    fun clearCache(context: Context): Int {
        memory.evictAll()
        failed.clear()
        inFlight.clear()
        val directory = File(context.applicationContext.cacheDir, CACHE_DIRECTORY)
        val files = directory.listFiles() ?: return 0
        return files.count { it.isFile && it.delete() }
    }

    private fun cacheFile(context: Context, url: String): File =
        File(File(context.cacheDir, CACHE_DIRECTORY), url.hashCode().toUInt().toString(16))

    /** Keeps the on-disk cache bounded by dropping the oldest files. */
    private fun trimDiskCache(directory: File?) {
        val files = directory?.listFiles() ?: return
        if (files.size <= MAX_DISK_ENTRIES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_DISK_ENTRIES)
            .forEach { it.delete() }
    }
}
