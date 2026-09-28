package dev.autobridge.iptv

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persisted IPTV sources, split by [IptvKind] so the TV and Radio grids stay independent.
 *
 * Credentials live in the app's private SharedPreferences, exactly like the existing bookmark and
 * profile stores. They are never logged and never leave the device except as part of the stream
 * URLs the user's own provider requires.
 */
object IptvSourceStore {
    private const val PREFS_NAME = "autobridge_iptv_sources"
    private const val KEY_ITEMS = "sources"

    fun list(context: Context, kind: IptvKind? = null): List<IptvSource> {
        val raw = prefs(context).getString(KEY_ITEMS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        val parsed = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index -> decode(array.optJSONObject(index)) }
        }.getOrDefault(emptyList())
        return if (kind == null) parsed else parsed.filter { it.kind == kind }
    }

    fun find(context: Context, id: String): IptvSource? = list(context).firstOrNull { it.id == id }

    /** Adds a new source, or replaces the existing one with the same id. Returns the stored value. */
    fun save(context: Context, source: IptvSource): IptvSource {
        val current = list(context).toMutableList()
        val index = current.indexOfFirst { it.id == source.id }
        if (index >= 0) current[index] = source else current += source
        persist(context, current)
        IptvCatalog.invalidate(source.id)
        return source
    }

    fun remove(context: Context, id: String) {
        persist(context, list(context).filterNot { it.id == id })
        IptvCatalog.invalidate(id)
    }

    /** Stable id for a freshly entered source; keeps saved entries addressable across restarts. */
    fun newId(): String = "src-" + java.util.UUID.randomUUID().toString().take(8)

    private fun persist(context: Context, sources: List<IptvSource>) {
        val array = JSONArray()
        sources.forEach { source ->
            array.put(
                JSONObject()
                    .put("id", source.id)
                    .put("name", source.name)
                    .put("kind", source.kind.name)
                    .put("type", source.type.name)
                    .put("url", source.url)
                    .put("username", source.username)
                    .put("password", source.password)
            )
        }
        prefs(context).edit { putString(KEY_ITEMS, array.toString()) }
    }

    private fun decode(json: JSONObject?): IptvSource? {
        val item = json ?: return null
        val id = item.optString("id").takeIf { it.isNotBlank() } ?: return null
        val url = item.optString("url").takeIf { it.isNotBlank() } ?: return null
        return IptvSource(
            id = id,
            name = item.optString("name").ifBlank { url },
            kind = runCatching { IptvKind.valueOf(item.optString("kind")) }.getOrDefault(IptvKind.TV),
            type = runCatching { IptvSourceType.valueOf(item.optString("type")) }
                .getOrDefault(IptvSourceType.M3U),
            url = url,
            username = item.optString("username"),
            password = item.optString("password")
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
