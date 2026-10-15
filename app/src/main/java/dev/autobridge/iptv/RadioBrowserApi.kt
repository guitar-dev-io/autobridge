package dev.autobridge.iptv

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Blocking access to radio-browser's station search: the URL, the request, and the reading of the
 * answer into playable [IptvEntry]s. Split from [RadioBrowserSearch] so the URL and the parsing are
 * tested on the JVM without a Looper. [search] does network I/O and must never run on main.
 */
internal object RadioBrowserApi {
    /** The same mirror [IptvDirectory]'s seeded radio lists already use. Always HTTPS. */
    const val BASE = "https://de1.api.radio-browser.info"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val MAX_BODY_CHARS = 2 * 1024 * 1024

    /** The search address for [query], or null when nothing meaningful is left to search for. */
    fun searchUrl(query: String, limit: Int = 50): String? {
        val q = RadioStationMatcher.apiQuery(query)
        if (q.isBlank()) return null
        val encoded = URLEncoder.encode(q, "UTF-8")
        return "$BASE/json/stations/search?name=$encoded&limit=${limit.coerceIn(1, 100)}" +
            "&hidebroken=true&order=votes&reverse=true"
    }

    /** Stations in a radio-browser JSON answer; garbage or a non-array body reads as none. */
    fun parse(json: String): List<IptvEntry> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<IptvEntry>()
        val seen = mutableSetOf<String>()
        for (index in 0 until array.length()) {
            val station = array.optJSONObject(index) ?: continue
            val title = station.optString("name").trim()
            if (title.isBlank()) continue
            if (station.optInt("lastcheckok", 1) == 0) continue
            val url = station.optString("url_resolved").trim().ifBlank { station.optString("url").trim() }
            if (url.isBlank()) continue
            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) continue
            if (!seen.add(url)) continue
            val bitrate = station.optInt("bitrate", 0)
            val codec = station.optString("codec").trim()
            val format = listOf(codec, if (bitrate > 0) "$bitrate kbps" else "")
                .filter { it.isNotBlank() }
                .joinToString(" ")
            out += IptvEntry(
                id = "rb:" + station.optString("stationuuid").ifBlank { url },
                title = title,
                categoryId = IptvCatalogData.ALL_CATEGORY_ID,
                type = IptvEntryType.LIVE,
                url = url,
                logo = station.optString("favicon").trim(),
                subtitle = listOf(station.optString("country").trim(), format)
                    .filter { it.isNotBlank() }
                    .joinToString(" • "),
                country = station.optString("countrycode").trim()
            )
        }
        return out
    }

    /** Blocking search. Throws on a network failure or a non-2xx answer; empty for a blank query. */
    fun search(query: String, limit: Int = 50): List<IptvEntry> {
        val url = searchUrl(query, limit) ?: return emptyList()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AutoBridge/1.0 (Android)")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("radio-browser returned HTTP $status")
            val body = StringBuilder()
            connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(8 * 1024)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    body.append(buffer, 0, read)
                    if (body.length > MAX_BODY_CHARS) throw IllegalStateException("radio-browser answer too large")
                }
            }
            return parse(body.toString())
        } finally {
            connection.disconnect()
        }
    }
}
