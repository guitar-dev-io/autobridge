package dev.autobridge.iptv

import android.content.Context
import dev.autobridge.core.crypto.SecretPrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Recently played and favourited IPTV entries.
 *
 * The playable URL is stored alongside the title so a recent item replays without reloading the
 * whole portal catalog — which is the slow step on large Xtream accounts.
 *
 * Those URLs are credentials. An Xtream stream URL carries the account's username and password in
 * its own path (see [XtreamCredentials]), so this file is written through [SecretPrefs] and excluded
 * from backup exactly like [IptvSourceStore], rather than being treated as ordinary history.
 */
object IptvHistoryStore {
    /** A remembered entry. [sourceId] is kept so the row can say where it came from. */
    data class Item(
        val sourceId: String,
        val title: String,
        val url: String,
        val type: IptvEntryType,
        val kind: IptvKind,
        val timestampMs: Long = System.currentTimeMillis(),
        // Replaying has to reach the same destination as the first play, and a YouTube page
        // handed to the player would only fail there.
        val playback: IptvPlayback = IptvPlayback.STREAM,
        // Kept so a remembered row looks like the row it was played from. An item stored by an
        // older version has none, and the row falls back to its accent initial as it always did.
        val logo: String = ""
    )

    private const val PREFS_NAME = "autobridge_iptv_history"
    private const val KEY_RECENT = "recent"
    private const val KEY_FAVORITES = "favorites"
    private const val MAX_RECENT = 30

    fun recent(context: Context, kind: IptvKind? = null): List<Item> =
        read(context, KEY_RECENT).let { items ->
            if (kind == null) items else items.filter { it.kind == kind }
        }

    fun favorites(context: Context, kind: IptvKind? = null): List<Item> =
        read(context, KEY_FAVORITES).let { items ->
            if (kind == null) items else items.filter { it.kind == kind }
        }

    fun isFavorite(context: Context, url: String): Boolean =
        read(context, KEY_FAVORITES).any { it.url == url }

    fun recordPlayback(context: Context, source: IptvSource, entry: IptvEntry) {
        if (entry.url.isBlank()) return
        val item = Item(
            source.id, entry.title, entry.url, entry.type, source.kind,
            playback = entry.playback, logo = entry.logo
        )
        val updated = (listOf(item) + read(context, KEY_RECENT).filterNot { it.url == item.url })
            .take(MAX_RECENT)
        write(context, KEY_RECENT, updated)
    }

    /** Adds or removes [entry] from favourites. Returns true when it is a favourite afterwards. */
    fun toggleFavorite(context: Context, source: IptvSource, entry: IptvEntry): Boolean {
        if (entry.url.isBlank()) return false
        val current = read(context, KEY_FAVORITES)
        val existing = current.any { it.url == entry.url }
        val updated = if (existing) {
            current.filterNot { it.url == entry.url }
        } else {
            current + Item(
                source.id, entry.title, entry.url, entry.type, source.kind,
                playback = entry.playback, logo = entry.logo
            )
        }
        write(context, KEY_FAVORITES, updated)
        return !existing
    }

    fun removeFavorite(context: Context, url: String) {
        write(context, KEY_FAVORITES, read(context, KEY_FAVORITES).filterNot { it.url == url })
    }

    fun clearRecent(context: Context) = write(context, KEY_RECENT, emptyList())

    private fun read(context: Context, key: String): List<Item> {
        val raw = SecretPrefs.read(prefs(context), PREFS_NAME, key)
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val url = item.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Item(
                    sourceId = item.optString("sourceId"),
                    title = item.optString("title").ifBlank { url },
                    url = url,
                    type = runCatching { IptvEntryType.valueOf(item.optString("type")) }
                        .getOrDefault(IptvEntryType.LIVE),
                    kind = runCatching { IptvKind.valueOf(item.optString("kind")) }
                        .getOrDefault(IptvKind.TV),
                    timestampMs = item.optLong("timestampMs"),
                    playback = runCatching { IptvPlayback.valueOf(item.optString("playback")) }
                        .getOrDefault(IptvPlayback.STREAM),
                    logo = item.optString("logo")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun write(context: Context, key: String, items: List<Item>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("sourceId", item.sourceId)
                    .put("title", item.title)
                    .put("url", item.url)
                    .put("type", item.type.name)
                    .put("kind", item.kind.name)
                    .put("timestampMs", item.timestampMs)
                    .put("playback", item.playback.name)
                    .put("logo", item.logo)
            )
        }
        SecretPrefs.write(prefs(context), PREFS_NAME, key, array.toString())
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
