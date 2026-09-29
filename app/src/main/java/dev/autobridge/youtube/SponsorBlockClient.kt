package dev.autobridge.youtube

import dev.autobridge.display.StructuredLog
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Blocking lookups against the public SponsorBlock database, with a small in-memory cache.
 *
 * The request carries only a four-character hash prefix of the video id (see
 * [SponsorBlock.hashPrefix]), never the id itself and never anything about the user, so the server
 * learns that someone is watching one of thousands of videos rather than which one.
 */
object SponsorBlockClient {
    private const val ENDPOINT = "https://sponsor.ajay.app/api/skipSegments"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val MAX_BODY_BYTES = 512 * 1024

    /** Keyed by video id and the categories asked for, so toggling a category re-queries. */
    private val cache = ConcurrentHashMap<String, List<SponsorSegment>>()

    fun cached(videoId: String, categories: Set<SponsorCategory>): List<SponsorSegment>? =
        cache[key(videoId, categories)]

    /** Blocking; call from a background thread. Returns an empty list when nothing is known. */
    fun segments(videoId: String, categories: Set<SponsorCategory>): List<SponsorSegment> {
        if (categories.isEmpty()) return emptyList()
        cache[key(videoId, categories)]?.let { return it }

        val query = categories.joinToString(",") { "\"${it.apiId}\"" }
        val url = "$ENDPOINT/${SponsorBlock.hashPrefix(videoId)}" +
            "?categories=%5B$query%5D&actionTypes=%5B%22skip%22%5D"

        val segments = runCatching { SponsorBlock.parse(fetch(url), videoId, categories) }
            .onFailure { StructuredLog.w("YOUTUBE", "SponsorBlock lookup failed: ${it.message}") }
            // A failed lookup is cached as "nothing to skip" only for this process run; the next
            // video re-queries. Retrying per timeupdate would hammer a public service.
            .getOrDefault(emptyList())
        cache[key(videoId, categories)] = segments
        return segments
    }

    private fun key(videoId: String, categories: Set<SponsorCategory>): String =
        videoId + "|" + categories.sortedBy { it.apiId }.joinToString(",") { it.apiId }

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            // 404 is the ordinary answer for "no segments for this prefix", not a failure.
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return "[]"
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            return connection.inputStream.use { stream ->
                // Bounded read: a public endpoint is not trusted to send something this device
                // should hold in memory whole.
                val buffer = ByteArray(8 * 1024)
                val body = StringBuilder()
                var total = 0
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_BODY_BYTES) throw IllegalStateException("Response too large")
                    body.append(String(buffer, 0, read, Charsets.UTF_8))
                }
                body.toString()
            }
        } finally {
            connection.disconnect()
        }
    }
}
