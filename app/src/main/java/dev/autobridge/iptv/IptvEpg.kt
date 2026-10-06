package dev.autobridge.iptv

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * "What is on now" for live channels, from the portal's own guide.
 *
 * Xtream portals answer `get_short_epg` per channel with the current and next programme, so a
 * channel row can say what is showing instead of only its name — a choice made at a glance rather
 * than by opening channels one after another. M3U playlists carry no per-channel guide endpoint
 * (their XMLTV files run to tens of megabytes), so they are not asked.
 *
 * Fetched only for the rows in view, a few at a time, and cached per channel for [FRESHNESS_MS]; a
 * channel with no guide is remembered as such so it is not asked again on every repaint.
 */
object IptvEpg {
    private const val FRESHNESS_MS = 10 * 60 * 1000L
    private const val TIMEOUT_MS = 6_000
    private const val MAX_BODY = 64 * 1024
    private const val LIVE_PREFIX = "live:"

    private data class Cached(val title: String?, val atMs: Long)

    private val cache = ConcurrentHashMap<String, Cached>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val worker = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "AutoBridgeEpg").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    /** The programme on now for [entry], if already known and fresh; null otherwise. */
    fun cached(source: IptvSource, entry: IptvEntry): String? {
        val key = key(source, entry) ?: return null
        val hit = cache[key] ?: return null
        return hit.title.takeIf { SystemClock.elapsedRealtime() - hit.atMs < FRESHNESS_MS }
    }

    /**
     * Fetches the guide for each of [entries] that is not fresh in the cache, and calls [onUpdate]
     * on the main thread after each answer lands. Only Xtream live channels are asked.
     */
    fun load(source: IptvSource, entries: List<IptvEntry>, onUpdate: () -> Unit) {
        val credentials = source.credentials ?: return
        entries.forEach { entry ->
            val key = key(source, entry) ?: return@forEach
            val hit = cache[key]
            if (hit != null && SystemClock.elapsedRealtime() - hit.atMs < FRESHNESS_MS) return@forEach
            if (!inFlight.add(key)) return@forEach
            val streamId = entry.id.removePrefix(LIVE_PREFIX)
            worker.execute {
                val title = runCatching {
                    parseNow(fetch(credentials.apiUrl("get_short_epg", mapOf("stream_id" to streamId, "limit" to "2"))))
                }.getOrNull()
                cache[key] = Cached(title, SystemClock.elapsedRealtime())
                inFlight.remove(key)
                if (title != null) main.post(onUpdate)
            }
        }
    }

    private fun key(source: IptvSource, entry: IptvEntry): String? =
        if (entry.type == IptvEntryType.LIVE && entry.id.startsWith(LIVE_PREFIX) && source.type == IptvSourceType.XTREAM) {
            source.id + "|" + entry.id
        } else {
            null
        }

    /**
     * The title of the listing airing at [nowSec] (epoch seconds) in a `get_short_epg` answer,
     * falling back to the first listing when the portal's clock and ours disagree. Titles are
     * base64 on most portals and plain on some; both are read. Null when there is no guide.
     */
    internal fun parseNow(json: String, nowSec: Long = System.currentTimeMillis() / 1000): String? {
        val listings = runCatching { JSONObject(json).optJSONArray("epg_listings") }.getOrNull() ?: return null
        var first: String? = null
        for (index in 0 until listings.length()) {
            val listing = listings.optJSONObject(index) ?: continue
            val title = decode(listing.optString("title")).takeIf { it.isNotBlank() } ?: continue
            if (first == null) first = title
            val start = listing.optString("start_timestamp").toLongOrNull()
            val stop = listing.optString("stop_timestamp").toLongOrNull()
            if (start != null && stop != null && nowSec in start until stop) return title
        }
        return first
    }

    private fun decode(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        val decoded = runCatching { String(Base64.getDecoder().decode(value), Charsets.UTF_8) }.getOrNull()
        // A plain title that happens to be valid base64 decodes to bytes that are not text.
        return if (decoded != null && decoded.none { it == '�' || (it.isISOControl() && it != '\n') }) {
            decoded.trim()
        } else {
            value
        }
    }

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "AutoBridge/1.0 (Android)")
        }
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            val body = connection.inputStream.bufferedReader().readText()
            if (body.length > MAX_BODY) error("guide too large")
            return body
        } finally {
            connection.disconnect()
        }
    }
}
