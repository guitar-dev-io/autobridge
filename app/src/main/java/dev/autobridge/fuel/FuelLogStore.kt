package dev.autobridge.fuel

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * The fill-up log, on this phone only. A small JSON list in shared preferences: a car's worth of
 * fill-ups is a few hundred rows a decade, which needs no database.
 */
object FuelLogStore {
    private const val PREFS = "autobridge_fuel"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_EV = "vehicle_is_ev"

    fun all(context: Context): List<FuelEntry> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            FuelEntry(
                id = item.optLong("id"),
                timeMs = item.optLong("time"),
                liters = item.optDouble("liters", 0.0),
                totalBaht = item.optDouble("baht", 0.0),
                odometerKm = if (item.has("odo") && !item.isNull("odo")) item.optDouble("odo") else null,
                station = item.optString("station"),
            ).takeIf { it.liters > 0 }
        }.sortedByDescending { it.timeMs }
    }

    fun add(context: Context, liters: Double, totalBaht: Double, odometerKm: Double?, station: String, timeMs: Long = System.currentTimeMillis()) {
        val entry = FuelEntry(timeMs, timeMs, liters, totalBaht, odometerKm, station.trim())
        write(context, all(context) + entry)
    }

    fun delete(context: Context, id: Long) = write(context, all(context).filterNot { it.id == id })

    /** The highest odometer logged, to suggest when the car has not reported one. */
    fun lastOdometer(context: Context): Double? = all(context).mapNotNull { it.odometerKm }.maxOrNull()

    /**
     * Whether the vehicle is an EV. The log then counts kWh charged instead of litres filled — the
     * same entries, a different unit — so one vehicle is either one or the other.
     */
    fun isEv(context: Context): Boolean = prefs(context).getBoolean(KEY_EV, false)

    fun setEv(context: Context, ev: Boolean) = prefs(context).edit { putBoolean(KEY_EV, ev) }

    /** The stored entries as JSON, for a backup; "[]" when there are none. */
    fun rawJson(context: Context): String = prefs(context).getString(KEY_ENTRIES, null) ?: "[]"

    /** Replaces the log with a backup's. Returns false, changing nothing, if it is not a list. */
    fun restore(context: Context, raw: String, ev: Boolean): Boolean {
        if (runCatching { JSONArray(raw) }.isFailure) return false
        prefs(context).edit {
            putString(KEY_ENTRIES, raw)
            putBoolean(KEY_EV, ev)
        }
        return true
    }

    private fun write(context: Context, entries: List<FuelEntry>) {
        val array = JSONArray()
        entries.sortedBy { it.timeMs }.forEach { entry ->
            array.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("time", entry.timeMs)
                    .put("liters", entry.liters)
                    .put("baht", entry.totalBaht)
                    .put("odo", entry.odometerKm ?: JSONObject.NULL)
                    .put("station", entry.station)
            )
        }
        prefs(context).edit { putString(KEY_ENTRIES, array.toString()) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
