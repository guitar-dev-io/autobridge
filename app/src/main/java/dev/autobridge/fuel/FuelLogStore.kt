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
                fuelType = item.optString("type"),
            ).takeIf { it.liters > 0 }
        }.sortedByDescending { it.timeMs }
    }

    fun add(
        context: Context,
        liters: Double,
        totalBaht: Double,
        odometerKm: Double?,
        station: String,
        fuelType: String = "",
        timeMs: Long = System.currentTimeMillis(),
    ) {
        // The id is its own: the time can be a day in the past, and two entries may share one.
        val id = maxOf(System.currentTimeMillis(), (all(context).maxOfOrNull { it.id } ?: 0L) + 1)
        val entry = FuelEntry(id, timeMs, liters, totalBaht, odometerKm, station.trim(), fuelType.trim())
        write(context, all(context) + entry)
    }

    /** Replaces the entry [id] with the edited figures; the date may be moved, which reorders the log. */
    fun update(
        context: Context,
        id: Long,
        liters: Double,
        totalBaht: Double,
        odometerKm: Double?,
        station: String,
        fuelType: String,
        timeMs: Long,
    ) = write(context, all(context).map {
        if (it.id == id) it.copy(
            timeMs = timeMs, liters = liters, totalBaht = totalBaht, odometerKm = odometerKm,
            station = station.trim(), fuelType = fuelType.trim()
        ) else it
    })

    fun delete(context: Context, id: Long) = write(context, all(context).filterNot { it.id == id })

    /** The grade of the latest fill-up that named one, offered again for the next. */
    fun lastFuelType(context: Context): String = all(context).firstOrNull { it.fuelType.isNotBlank() }?.fuelType.orEmpty()

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
                    .put("type", entry.fuelType)
            )
        }
        prefs(context).edit { putString(KEY_ENTRIES, array.toString()) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
