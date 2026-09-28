package dev.autobridge.iptv

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Recently played and favourited IPTV entries.
 *
 * The playable URL is stored alongside the title so a recent item replays without reloading the
 * whole portal catalog — which is the slow step on large Xtream accounts.
 */
object IptvHistoryStore {
    /** A remembered entry. [sourceId] is kept so the row can say where it came from. */
    data class Item(
        val sourceId: String,
        val title: String,
        val url: String,
        val type: IptvEntryType,
        val kind: IptvKind,
        val timestampMs: Long = System.currentTimeMillis()
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
        val item = Item(source.id, entry.title, entry.url, entry.type, source.kind)
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
            current + Item(source.id, entry.title, entry.url, entry.type, source.kind)
        }
        write(context, KEY_FAVORITES, updated)
        return !existing
    }

    fun removeFavorite(context: Context, url: String) {
        write(context, KEY_FAVORITES, read(context, KEY_FAVORITES).filterNot { it.url == url })
    }

    fun clearRecent(context: Context) = write(context, KEY_RECENT, emptyList())

    private fun read(context: Context, key: String): List<Item> {
        val raw = prefs(context).getString(key, null).orEmpty()
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
                    timestampMs = item.optLong("timestampMs")
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
            )
        }
        prefs(context).edit { putString(key, array.toString()) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
