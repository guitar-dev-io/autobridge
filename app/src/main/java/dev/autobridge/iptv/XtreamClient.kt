package dev.autobridge.iptv

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Blocking HTTP access to an Xtream Codes portal and to plain M3U playlists.
 *
 * Every method here does network I/O and must be called off the main thread; [IptvCatalog] owns
 * the threading. Responses are read defensively: portals differ in field naming and in whether
 * `player_api.php` exists at all, so a failed API call falls back to the `get.php` playlist
 * rather than leaving the user with an empty screen.
 */
internal object XtreamClient {
    private const val TAG = "XtreamClient"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 25_000
    private const val MAX_BODY_BYTES = 24 * 1024 * 1024

    /** Loads a whole source into categories + playable entries. Throws on unrecoverable failure. */
    fun load(source: IptvSource): IptvCatalogData = when (source.type) {
        IptvSourceType.XTREAM -> loadXtream(source)
        // A playlist the user filed under Radio is taken at its word: no audio heuristic.
        IptvSourceType.M3U -> loadPlaylist(source.url, keepOnlyRadio = false)
    }

    /** Episodes for one series folder, loaded on demand (`get_series_info`). */
    fun seriesEpisodes(source: IptvSource, seriesId: String): List<IptvEntry> {
        val credentials = source.credentials ?: return emptyList()
        val body = runCatching {
            fetch(credentials.apiUrl("get_series_info", mapOf("series_id" to seriesId)))
        }.getOrNull() ?: return emptyList()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val seasons = root.optJSONObject("episodes") ?: return emptyList()

        val episodes = mutableListOf<IptvEntry>()
        seasons.keys().forEach { season ->
            val list = seasons.optJSONArray(season) ?: return@forEach
            for (index in 0 until list.length()) {
                val episode = list.optJSONObject(index) ?: continue
                val id = episode.optString("id").takeIf { it.isNotBlank() } ?: continue
                val extension = episode.optString("container_extension").ifBlank { "mp4" }
                val title = episode.optString("title").ifBlank {
                    "S$season E${episode.optString("episode_num")}"
                }
                episodes += IptvEntry(
                    id = id,
                    title = title,
                    categoryId = seriesId,
                    type = IptvEntryType.SERIES,
                    url = credentials.seriesUrl(id, extension),
                    subtitle = "Season $season",
                    seriesId = seriesId
                )
            }
        }
        return episodes.sortedBy { it.subtitle }
    }

    private fun loadXtream(source: IptvSource): IptvCatalogData {
        val credentials = source.credentials
            ?: throw IllegalArgumentException("Invalid Xtream portal or credentials")

        // A failing player_api.php is common on resold portals; the m3u_plus playlist still works.
        val api = runCatching { loadXtreamApi(source, credentials) }
        api.exceptionOrNull()?.let { Log.w(TAG, "player_api.php unavailable, using get.php", it) }
        val fromApi = api.getOrNull()
        if (fromApi != null && fromApi.entries.isNotEmpty()) return fromApi
        // One portal playlist carries live TV, radio, movies and series together, so the Radio
        // grid does need the audio heuristic here.
        return loadPlaylist(credentials.playlistUrl(), keepOnlyRadio = source.kind == IptvKind.RADIO)
    }

    private fun loadXtreamApi(source: IptvSource, credentials: XtreamCredentials): IptvCatalogData {
        val categories = mutableListOf<IptvCategory>()
        val entries = mutableListOf<IptvEntry>()

        // Live channels feed both the TV and the Radio grid; Radio keeps only audio-ish groups.
        val liveCategories = categoryList(credentials, "get_live_categories")
        val live = jsonArray(credentials.apiUrl("get_live_streams"))
        for (index in 0 until live.length()) {
            val stream = live.optJSONObject(index) ?: continue
            val id = stream.optString("stream_id").takeIf { it.isNotBlank() } ?: continue
            entries += IptvEntry(
                id = "live:$id",
                title = stream.optString("name").ifBlank { "Channel $id" },
                categoryId = stream.optString("category_id").ifBlank { IptvCatalogData.ALL_CATEGORY_ID },
                type = IptvEntryType.LIVE,
                url = credentials.liveUrl(id),
                logo = IptvLogos.resolve(credentials.portal, stream.optString("stream_icon")),
                catchupDays = stream.optInt("tv_archive_duration", 0)
                    .takeIf { stream.optInt("tv_archive", 0) == 1 } ?: 0
            )
        }
        categories += liveCategories

        // Radio sources stop at live audio streams; movies and series are TV-only content.
        if (source.kind == IptvKind.TV) {
            val vodCategories = categoryList(credentials, "get_vod_categories")
            val vod = jsonArray(credentials.apiUrl("get_vod_streams"))
            for (index in 0 until vod.length()) {
                val movie = vod.optJSONObject(index) ?: continue
                val id = movie.optString("stream_id").takeIf { it.isNotBlank() } ?: continue
                entries += IptvEntry(
                    id = "vod:$id",
                    title = movie.optString("name").ifBlank { "Movie $id" },
                    categoryId = movie.optString("category_id").ifBlank { IptvCatalogData.ALL_CATEGORY_ID },
                    type = IptvEntryType.MOVIE,
                    url = credentials.vodUrl(id, movie.optString("container_extension")),
                    logo = IptvLogos.resolve(credentials.portal, movie.optString("stream_icon")),
                    subtitle = "Movie"
                )
            }
            categories += vodCategories.map { it.copy(id = it.id, name = "Movies · ${it.name}") }

            val seriesCategories = categoryList(credentials, "get_series_categories")
            val series = jsonArray(credentials.apiUrl("get_series"))
            for (index in 0 until series.length()) {
                val show = series.optJSONObject(index) ?: continue
                val id = show.optString("series_id").takeIf { it.isNotBlank() } ?: continue
                entries += IptvEntry(
                    // Series carry no direct URL: episodes load on demand from get_series_info.
                    id = "series:$id",
                    title = show.optString("name").ifBlank { "Series $id" },
                    categoryId = show.optString("category_id").ifBlank { IptvCatalogData.ALL_CATEGORY_ID },
                    type = IptvEntryType.SERIES,
                    url = "",
                    logo = IptvLogos.resolve(credentials.portal, show.optString("cover")),
                    subtitle = "Series",
                    seriesId = id
                )
            }
            categories += seriesCategories.map { it.copy(name = "Series · ${it.name}") }
        }

        return finish(categories, entries)
    }

    private fun categoryList(credentials: XtreamCredentials, action: String): List<IptvCategory> {
        val array = runCatching { jsonArray(credentials.apiUrl(action)) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("category_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            IptvCategory(id, item.optString("category_name").ifBlank { "Category $id" })
        }
    }

    private fun loadPlaylist(url: String, keepOnlyRadio: Boolean): IptvCatalogData {
        val channels = M3uParser.parse(fetch(url))
        val entries = channels.mapIndexed { index, channel ->
            // Curated lists carry their notes in the display name and their warnings in the URL;
            // both are read here so the row can say "YouTube · Geo-blocked" instead of failing
            // inside the player with a parse error.
            val label = IptvPlaylistConventions.label(channel.name)
            val webPage = IptvPlaylistConventions.isWebPage(channel.url)
            IptvEntry(
                id = "m3u:$index",
                title = label.title,
                categoryId = channel.group.ifBlank { IptvCatalogData.ALL_CATEGORY_ID },
                type = IptvEntryType.LIVE,
                url = channel.url,
                // A logo is routinely written relative to the list it came from, so the playlist
                // address is what resolves it; see [IptvLogos].
                logo = IptvLogos.resolve(url, channel.logo),
                subtitle = (listOf(channel.group) + label.hints)
                    .filter { it.isNotBlank() }
                    // Same separator the rows themselves use, so a row never mixes two.
                    .joinToString(" • "),
                playback = if (webPage) IptvPlayback.WEB_PAGE else IptvPlayback.STREAM
            )
        }.let { if (keepOnlyRadio) it.filter(::looksLikeRadio) else it }

        val categories = entries.map { it.categoryId }.distinct()
            .filter { it != IptvCatalogData.ALL_CATEGORY_ID }
            .map { IptvCategory(it, it) }
        return finish(categories, entries)
    }

    /**
     * Separates audio from video inside a mixed portal playlist, by group/name wording or an audio
     * file extension.
     *
     * The guess is far too narrow to run over a playlist the user chose themselves: a real station
     * list from radio-browser contains "88 nice peak", "90.5 Delight" and "97qfm", none of which
     * match, so filtering a user-added Radio playlist emptied most of it. It is therefore applied
     * only where one URL genuinely mixes both kinds — an Xtream portal's m3u_plus playlist.
     */
    private fun looksLikeRadio(entry: IptvEntry): Boolean {
        val haystack = (entry.title + " " + entry.subtitle).lowercase()
        if (RADIO_WORDS.any { it in haystack }) return true
        val path = entry.url.substringBefore('?').lowercase()
        return AUDIO_EXTENSIONS.any { path.endsWith(it) }
    }

    /** Drops empty categories, sorts by name, and prepends the "All" bucket. */
    private fun finish(categories: List<IptvCategory>, entries: List<IptvEntry>): IptvCatalogData {
        val counts = entries.groupingBy { it.categoryId }.eachCount()
        val populated = categories.distinctBy { it.id }
            .map { it.copy(count = counts[it.id] ?: 0) }
            .filter { it.count > 0 }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        val all = IptvCategory(IptvCatalogData.ALL_CATEGORY_ID, "All", entries.size)
        return IptvCatalogData(listOf(all) + populated, entries)
    }

    private fun jsonArray(url: String): JSONArray {
        val body = fetch(url).trim()
        if (body.startsWith("[")) return JSONArray(body)
        // Some portals answer an error object instead of a list; treat that as "no content".
        if (body.startsWith("{")) return JSONArray()
        throw IllegalStateException("Unexpected portal response")
    }

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            // Several portals reject the default Java agent outright.
            setRequestProperty("User-Agent", "AutoBridge/1.0 (Android)")
            setRequestProperty("Accept", "*/*")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("Portal returned HTTP $status")
            }
            val body = StringBuilder()
            connection.inputStream.bufferedReader().use { reader: BufferedReader ->
                val buffer = CharArray(8 * 1024)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    body.append(buffer, 0, read)
                    if (body.length > MAX_BODY_BYTES) {
                        throw IllegalStateException("Playlist too large")
                    }
                }
            }
            return body.toString()
        } finally {
            connection.disconnect()
        }
    }

    // Matched against the names a provider gives its streams, so the words stay bilingual in code
    // rather than moving to res/values-th: a Thai playlist has Thai channel names whatever language
    // the UI happens to be in. Same reasoning as the voice-command vocabularies.
    private val RADIO_WORDS = listOf("radio", "fm ", " fm", "am ", "music", "audio", "วิทยุ")
    private val AUDIO_EXTENSIONS = listOf(".mp3", ".aac", ".m4a", ".ogg", ".opus", ".flac", ".wav")
}
