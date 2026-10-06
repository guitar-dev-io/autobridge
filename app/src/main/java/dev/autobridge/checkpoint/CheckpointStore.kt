package dev.autobridge.checkpoint

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * The checkpoints the driver marked, on this phone only, and whether they allowed their location to
 * be read to measure the distance to them.
 *
 * Nothing here is shared or reported: the points are the driver's own notes, and the driver's own
 * position is never stored.
 */
object CheckpointStore {
    private const val PREFS = "autobridge_checkpoints"
    private const val KEY_POINTS = "points"
    private const val KEY_LOCATION_OK = "location_allowed"

    fun all(context: Context): List<Checkpoint> {
        val raw = prefs(context).getString(KEY_POINTS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            if (!item.has("lat") || !item.has("lon")) return@mapNotNull null
            Checkpoint(item.optLong("id"), item.optString("name"), item.optDouble("lat"), item.optDouble("lon"), item.optLong("time"))
        }.sortedByDescending { it.createdMs }
    }

    fun add(context: Context, name: String, lat: Double, lon: Double, nowMs: Long = System.currentTimeMillis()) {
        val id = maxOf(nowMs, (all(context).maxOfOrNull { it.id } ?: 0L) + 1)
        write(context, all(context) + Checkpoint(id, name.trim(), lat, lon, nowMs))
    }

    fun delete(context: Context, id: Long) = write(context, all(context).filterNot { it.id == id })

    /** The driver agreed to their location being read on the car's checkpoint screen. */
    fun locationAllowed(context: Context): Boolean = prefs(context).getBoolean(KEY_LOCATION_OK, false)

    fun setLocationAllowed(context: Context, allowed: Boolean) = prefs(context).edit { putBoolean(KEY_LOCATION_OK, allowed) }

    /** The stored points as JSON, for a backup; "[]" when there are none. */
    fun rawJson(context: Context): String = prefs(context).getString(KEY_POINTS, null) ?: "[]"

    /** Replaces the points with a backup's. Returns false, changing nothing, if it is not a list. */
    fun restore(context: Context, raw: String): Boolean {
        if (runCatching { JSONArray(raw) }.isFailure) return false
        prefs(context).edit { putString(KEY_POINTS, raw) }
        return true
    }

    private fun write(context: Context, points: List<Checkpoint>) {
        val array = JSONArray()
        points.sortedBy { it.createdMs }.forEach {
            array.put(
                JSONObject().put("id", it.id).put("name", it.name).put("lat", it.lat).put("lon", it.lon).put("time", it.createdMs)
            )
        }
        prefs(context).edit { putString(KEY_POINTS, array.toString()) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
