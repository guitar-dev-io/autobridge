package dev.autobridge.desktop.install

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads one file with resume and an integrity check.
 *
 * - Bytes go to `<target>.part`. A later call continues it with an HTTP `Range` request; a server
 *   that answers 200 instead of 206 restarts it from zero.
 * - When [expectedSha256] is given, the finished file must hash to it or it is deleted and
 *   [IntegrityException] is thrown. The `.part` is only renamed to [target] after that check, so a
 *   file at [target] has always been verified.
 * - Redirects are followed by hand: a registry hands out a short-lived CDN URL, and the bearer
 *   token for the registry must not be sent to that other host.
 */
class VerifiedDownloader(
    private val userAgent: String = "AutoBridge-DesktopBrowser",
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    class IntegrityException(message: String) : IOException(message)
    class CancelledException : IOException("download cancelled")

    /** [done] and [total] are bytes; [total] is -1 when the server does not say. */
    fun interface Progress { fun update(done: Long, total: Long) }

    fun download(
        url: String,
        target: File,
        expectedSha256: String?,
        headers: Map<String, String> = emptyMap(),
        isCancelled: () -> Boolean = { false },
        progress: Progress = Progress { _, _ -> },
    ): File {
        if (target.isFile && expectedSha256 != null && sha256(target) == expectedSha256.lowercase()) {
            progress.update(target.length(), target.length())
            return target
        }
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        var attempt = 0
        while (true) {
            try {
                fetch(url, part, headers, isCancelled, progress)
                break
            } catch (e: CancelledException) {
                throw e
            } catch (e: IOException) {
                // Network drop: resume from what is on disk, a few times, then give up.
                if (++attempt >= MAX_ATTEMPTS) throw e
                Thread.sleep(1500L * attempt)
            }
        }
        if (expectedSha256 != null) {
            val actual = sha256(part)
            if (actual != expectedSha256.lowercase()) {
                part.delete()
                throw IntegrityException("SHA-256 mismatch for ${target.name}: expected $expectedSha256, got $actual")
            }
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IOException("cannot move ${part.name} into place")
        return target
    }

    /** Reads a small text resource (a token, a key) fully into memory. */
    fun fetchText(url: String, headers: Map<String, String> = emptyMap(), limit: Int = 1 shl 20): String {
        val connection = connect(url, headers, rangeFrom = 0)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for $url")
            return connection.inputStream.use { readLimited(it, limit) }
        } finally {
            connection.disconnect()
        }
    }

    fun fetchBytes(url: String, headers: Map<String, String> = emptyMap(), limit: Int = 1 shl 20): ByteArray {
        val connection = connect(url, headers, rangeFrom = 0)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for $url")
            return connection.inputStream.use { it.readBytesLimited(limit) }
        } finally {
            connection.disconnect()
        }
    }

    /** Anonymous pull token for a public Docker Hub repository. */
    fun dockerHubToken(repository: String): String {
        val body = fetchText("https://auth.docker.io/token?service=registry.docker.io&scope=repository:$repository:pull")
        return JSONObject(body).getString("token")
    }

    private fun fetch(url: String, part: File, headers: Map<String, String>, isCancelled: () -> Boolean, progress: Progress) {
        val have = if (part.isFile) part.length() else 0L
        val connection = connect(url, headers, rangeFrom = have)
        try {
            val code = connection.responseCode
            val append = code == HttpURLConnection.HTTP_PARTIAL && have > 0
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                if (code == 416 && have > 0) return // already complete
                throw IOException("HTTP $code for ${URL(url).host}")
            }
            val length = connection.getHeaderFieldLong("Content-Length", -1)
            val total = if (length < 0) -1 else if (append) have + length else length
            var done = if (append) have else 0L
            FileOutputStream(part, append).use { out ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(128 * 1024)
                    var lastReport = 0L
                    while (true) {
                        if (isCancelled()) throw CancelledException()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        if (done - lastReport >= 256 * 1024) {
                            progress.update(done, total)
                            lastReport = done
                        }
                    }
                }
            }
            progress.update(done, total)
            if (total >= 0 && done < total) throw IOException("connection closed at $done of $total bytes")
        } finally {
            connection.disconnect()
        }
    }

    private fun connect(url: String, headers: Map<String, String>, rangeFrom: Long): HttpURLConnection {
        var current = URL(url)
        var sendHeaders = headers
        repeat(MAX_REDIRECTS) {
            val connection = openConnection(current).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", userAgent)
                for ((k, v) in sendHeaders) setRequestProperty(k, v)
                if (rangeFrom > 0) setRequestProperty("Range", "bytes=$rangeFrom-")
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location") ?: throw IOException("redirect without Location")
                connection.disconnect()
                val next = URL(current, location)
                if (next.protocol != "https") throw IOException("refusing non-HTTPS redirect to ${next.host}")
                // Credentials belong to the host they were issued for.
                if (next.host != current.host) sendHeaders = sendHeaders.filterKeys { !it.equals("Authorization", true) }
                current = next
                return@repeat
            }
            return connection
        }
        throw IOException("too many redirects for $url")
    }

    companion object {
        private const val MAX_ATTEMPTS = 4
        private const val MAX_REDIRECTS = 6

        fun sha256(file: File): String = file.inputStream().use { sha256(it) }

        fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun readLimited(input: InputStream, limit: Int): String = String(input.readBytesLimited(limit), Charsets.UTF_8)

        private fun InputStream.readBytesLimited(limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > limit) throw IOException("response larger than $limit bytes")
            }
            return out.toByteArray()
        }
    }
}
