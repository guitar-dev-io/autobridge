package dev.autobridge.remote

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent, reactive, customizable Quick Commands for the Mobile Remote (spec §6).
 *
 * Each quick command maps a label + icon to a [CommandType] (+ optional payload). They all flow
 * through the SAME [AutoBridgeCommandBus] / [AutoBridgeCommandRouter] — there is no separate action
 * path. Supports add / remove / reorder and seeds a sensible default set on first run.
 */
object QuickCommandStore {
    private const val PREFS_NAME = "autobridge_quick_commands"
    private const val KEY_ITEMS = "items"
    private const val KEY_SEEDED = "seeded"

    data class QuickCommand(
        val id: String,
        val label: String,
        val icon: String,
        val type: CommandType,
        val payload: String? = null,
        val sortOrder: Int = 0
    )

    private val _items = MutableStateFlow<List<QuickCommand>>(emptyList())
    val items: StateFlow<List<QuickCommand>> = _items.asStateFlow()

    fun restore(context: Context) {
        seedDefaultsIfNeeded(context)
        _items.value = load(context)
    }

    fun add(context: Context, label: String, icon: String, type: CommandType, payload: String? = null) {
        val items = _items.value.toMutableList()
        items.add(
            QuickCommand(
                id = "qc_${System.currentTimeMillis()}_${items.size}",
                label = label.ifBlank { type.name },
                icon = icon,
                type = type,
                payload = payload,
                sortOrder = items.size
            )
        )
        commit(context, items)
    }

    fun remove(context: Context, id: String) {
        commit(context, _items.value.filterNot { it.id == id })
    }

    fun move(context: Context, id: String, up: Boolean) {
        val items = _items.value.toMutableList()
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return
        val target = if (up) index - 1 else index + 1
        if (target < 0 || target >= items.size) return
        val tmp = items[index]; items[index] = items[target]; items[target] = tmp
        commit(context, items)
    }

    fun replaceAll(context: Context, items: List<QuickCommand>) = commit(context, items)

    private fun seedDefaultsIfNeeded(context: Context) {
        if (prefs(context).getBoolean(KEY_SEEDED, false)) return
        prefs(context).edit { putBoolean(KEY_SEEDED, true) }
        if (load(context).isNotEmpty()) return
        val defaults = listOf(
            QuickCommand("qc_browser", "Browser", "🌐", CommandType.OPEN_BROWSER, null, 0),
            QuickCommand("qc_youtube", "YouTube", "▶", CommandType.OPEN_URL, "https://m.youtube.com", 1),
            QuickCommand("qc_maps", "Maps", "🗺", CommandType.OPEN_URL, "https://maps.google.com", 2),
            QuickCommand("qc_spotify", "Spotify", "🎵", CommandType.OPEN_URL, "https://open.spotify.com", 3),
            QuickCommand("qc_mirror", "Mirror", "📱", CommandType.OPEN_MIRROR, null, 4),
            QuickCommand("qc_fullscreen", "Fullscreen", "⛶", CommandType.ENTER_FULLSCREEN, null, 5),
            QuickCommand("qc_reload", "Reload", "↻", CommandType.RELOAD, null, 6),
            QuickCommand("qc_agent", "Agent", "✦", CommandType.OPEN_AGENT, null, 7)
        )
        persist(context, defaults)
    }

    private fun commit(context: Context, items: List<QuickCommand>) {
        val reindexed = items.mapIndexed { i, c -> c.copy(sortOrder = i) }
        _items.value = reindexed
        persist(context, reindexed)
    }

    private fun load(context: Context): List<QuickCommand> {
        val raw = prefs(context).getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val type = runCatching { CommandType.valueOf(obj.getString("type")) }.getOrNull()
                    ?: return@mapNotNull null
                QuickCommand(
                    id = obj.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                    label = obj.optString("label"),
                    icon = obj.optString("icon"),
                    type = type,
                    payload = obj.optString("payload").takeIf { it.isNotBlank() },
                    sortOrder = obj.optInt("order", index)
                )
            }
        }.getOrDefault(emptyList()).sortedBy { it.sortOrder }
    }

    private fun persist(context: Context, items: List<QuickCommand>) {
        val array = JSONArray()
        items.sortedBy { it.sortOrder }.forEach { c ->
            array.put(
                JSONObject().apply {
                    put("id", c.id)
                    put("label", c.label)
                    put("icon", c.icon)
                    put("type", c.type.name)
                    put("payload", c.payload.orEmpty())
                    put("order", c.sortOrder)
                }
            )
        }
        prefs(context).edit { putString(KEY_ITEMS, array.toString()) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
