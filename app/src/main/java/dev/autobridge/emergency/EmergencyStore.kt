package dev.autobridge.emergency

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** The emergency card, on this phone only, as a small JSON list. */
object EmergencyStore {
    private const val PREFS = "autobridge_emergency"
    private const val KEY_ENTRIES = "entries"

    fun all(context: Context): List<EmergencyEntry> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            EmergencyEntry(item.optLong("id"), item.optString("label"), item.optString("value"))
                .takeIf { it.label.isNotBlank() && it.value.isNotBlank() }
        }
    }

    fun add(context: Context, label: String, value: String, nowMs: Long = System.currentTimeMillis()) {
        val id = maxOf(nowMs, (all(context).maxOfOrNull { it.id } ?: 0L) + 1)
        write(context, all(context) + EmergencyEntry(id, label.trim(), value.trim()))
    }

    fun delete(context: Context, id: Long) = write(context, all(context).filterNot { it.id == id })

    private fun write(context: Context, entries: List<EmergencyEntry>) {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("id", it.id).put("label", it.label).put("value", it.value)) }
        prefs(context).edit { putString(KEY_ENTRIES, array.toString()) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
