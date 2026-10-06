package dev.autobridge.maintenance

import android.content.Context
import androidx.core.content.edit
import dev.autobridge.fuel.CarVehicleData
import dev.autobridge.fuel.FuelLogStore
import org.json.JSONArray
import org.json.JSONObject

/** The maintenance list, on this phone only, as a small JSON list like the fuel log. */
object MaintenanceStore {
    private const val PREFS = "autobridge_maintenance"
    private const val KEY_ITEMS = "items"

    fun all(context: Context): List<MaintenanceItem> {
        val raw = prefs(context).getString(KEY_ITEMS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            MaintenanceItem(
                id = item.optLong("id"),
                name = item.optString("name"),
                intervalKm = item.optInt("km"),
                intervalMonths = item.optInt("months"),
                lastKm = if (item.has("last_km") && !item.isNull("last_km")) item.optDouble("last_km") else null,
                lastTimeMs = item.optLong("last_time"),
            ).takeIf { it.name.isNotBlank() && (it.intervalKm > 0 || it.intervalMonths > 0) }
        }
    }

    fun add(context: Context, name: String, intervalKm: Int, intervalMonths: Int, lastKm: Double?, nowMs: Long = System.currentTimeMillis()) {
        val id = maxOf(nowMs, (all(context).maxOfOrNull { it.id } ?: 0L) + 1)
        write(context, all(context) + MaintenanceItem(id, name.trim(), intervalKm, intervalMonths, lastKm, nowMs))
    }

    /** Records [id] as done now, at [km] (or the date alone when no odometer is known). */
    fun markDone(context: Context, id: Long, km: Double?, nowMs: Long = System.currentTimeMillis()) =
        write(context, all(context).map { if (it.id == id) it.copy(lastKm = km ?: it.lastKm, lastTimeMs = nowMs) else it })

    fun delete(context: Context, id: Long) = write(context, all(context).filterNot { it.id == id })

    /**
     * The best odometer known: the car's last report or the highest one logged with a fill-up,
     * whichever is higher (an odometer only goes up). Null when neither exists.
     */
    fun currentOdometer(context: Context): Double? =
        listOfNotNull(CarVehicleData.lastOdometer(context)?.first, FuelLogStore.lastOdometer(context)).maxOrNull()

    /** The stored items as JSON, for a backup; "[]" when there are none. */
    fun rawJson(context: Context): String = prefs(context).getString(KEY_ITEMS, null) ?: "[]"

    /** Replaces the list with a backup's. Returns false, changing nothing, if it is not a list. */
    fun restore(context: Context, raw: String): Boolean {
        if (runCatching { JSONArray(raw) }.isFailure) return false
        prefs(context).edit { putString(KEY_ITEMS, raw) }
        return true
    }

    private fun write(context: Context, items: List<MaintenanceItem>) {
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("km", it.intervalKm)
                    .put("months", it.intervalMonths)
                    .put("last_km", it.lastKm ?: JSONObject.NULL)
                    .put("last_time", it.lastTimeMs)
            )
        }
        prefs(context).edit { putString(KEY_ITEMS, array.toString()) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
