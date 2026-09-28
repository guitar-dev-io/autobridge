package dev.autobridge.apps

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dynamic, persistent Quick Launch shortcuts for the Home Dashboard.
 *
 * Unlike [QuickAppsStore] (which mirrors installed phone apps for the mirror launcher), Quick Launch
 * is a free-form, user-ordered list of shortcuts that can:
 *  - open a URL in the built-in car browser (URL),
 *  - trigger an internal AutoBridge feature/action (INTERNAL, payload = action id),
 *  - launch an installed phone app (APP, payload = package name).
 *
 * Supports add / remove / reorder. Stored as a JSON array in SharedPreferences so it round-trips
 * without a schema migration. The Home screen seeds a small default set on first run.
 */
object QuickLaunchStore {
    private const val PREFS_NAME = "autobridge_quick_launch"
    private const val KEY_ITEMS = "items"
    private const val KEY_SEEDED = "seeded"

    enum class Kind { URL, INTERNAL, APP }

    data class Shortcut(
        val id: String,
        val label: String,
        val kind: Kind,
        val payload: String,
        val sortOrder: Int = 0
    )

    fun list(context: Context): List<Shortcut> {
        val raw = prefs(context).getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val kind = runCatching { Kind.valueOf(obj.getString("kind")) }.getOrNull() ?: return@mapNotNull null
                Shortcut(
                    id = obj.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                    label = obj.optString("label"),
                    kind = kind,
                    payload = obj.optString("payload"),
                    sortOrder = obj.optInt("order", index)
                )
            }
        }.getOrDefault(emptyList()).sortedBy { it.sortOrder }
    }

    fun add(context: Context, label: String, kind: Kind, payload: String): Shortcut {
        val items = list(context).toMutableList()
        val shortcut = Shortcut(
            id = "ql_${System.currentTimeMillis()}_${items.size}",
            label = label.ifBlank { payload },
            kind = kind,
            payload = payload,
            sortOrder = items.size
        )
        items.add(shortcut)
        persist(context, items)
        return shortcut
    }

    fun remove(context: Context, id: String) {
        val items = list(context).filterNot { it.id == id }
        persist(context, reindex(items))
    }

    /** Moves a shortcut up or down one slot and rewrites the sort order. */
    fun move(context: Context, id: String, up: Boolean) {
        val items = list(context).toMutableList()
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return
        val target = if (up) index - 1 else index + 1
        if (target < 0 || target >= items.size) return
        val tmp = items[index]
        items[index] = items[target]
        items[target] = tmp
        persist(context, reindex(items))
    }

    fun replaceAll(context: Context, shortcuts: List<Shortcut>) {
        persist(context, reindex(shortcuts))
    }

    /** Seeds a small default set once, so the Home screen isn't empty on first launch. */
    fun seedDefaultsIfNeeded(context: Context) {
        if (prefs(context).getBoolean(KEY_SEEDED, false)) return
        prefs(context).edit { putBoolean(KEY_SEEDED, true) }
        if (list(context).isNotEmpty()) return
        val defaults = listOf(
            Shortcut("ql_seed_google", "Google", Kind.URL, "https://www.google.com", 0),
            Shortcut("ql_seed_youtube", "YouTube", Kind.URL, "https://m.youtube.com", 1),
            Shortcut("ql_seed_maps", "Maps", Kind.URL, "https://maps.google.com", 2)
        )
        persist(context, defaults)
    }

    private fun reindex(items: List<Shortcut>): List<Shortcut> =
        items.mapIndexed { index, shortcut -> shortcut.copy(sortOrder = index) }

    private fun persist(context: Context, items: List<Shortcut>) {
        val array = JSONArray()
        items.sortedBy { it.sortOrder }.forEach { shortcut ->
            array.put(
                JSONObject().apply {
                    put("id", shortcut.id)
                    put("label", shortcut.label)
                    put("kind", shortcut.kind.name)
                    put("payload", shortcut.payload)
                    put("order", shortcut.sortOrder)
                }
            )
        }
        prefs(context).edit { putString(KEY_ITEMS, array.toString()) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
