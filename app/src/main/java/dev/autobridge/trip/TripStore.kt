package dev.autobridge.trip

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** Trips, on this phone only, as a small JSON list like the fuel log. */
object TripStore {
    private const val PREFS = "autobridge_trips"
    private const val KEY_TRIPS = "trips"

    fun all(context: Context): List<Trip> {
        val raw = prefs(context).getString(KEY_TRIPS, null) ?: return emptyList()
        return parse(raw).sortedByDescending { it.startMs }
    }

    fun active(context: Context): Trip? = all(context).firstOrNull { it.active }

    /** The party size of the last trip, offered again for the next. */
    fun lastPeople(context: Context): Int = all(context).firstOrNull()?.people ?: 1

    /** Starts a trip; an earlier one still going is left as it is (one at a time is the UI's job). */
    fun start(context: Context, startKm: Double?, people: Int, nowMs: Long = System.currentTimeMillis()): Trip {
        val trip = Trip(nowMs, nowMs, 0L, startKm, null, people.coerceAtLeast(1))
        write(context, all(context) + trip)
        return trip
    }

    fun finish(context: Context, id: Long, endKm: Double?, otherBaht: Double, bahtPerKm: Double?, nowMs: Long = System.currentTimeMillis()) =
        write(context, all(context).map {
            if (it.id == id) it.copy(endMs = nowMs, endKm = endKm, otherBaht = otherBaht, bahtPerKm = bahtPerKm) else it
        })

    fun delete(context: Context, id: Long) = write(context, all(context).filterNot { it.id == id })

    /** The stored JSON, for a backup; "[]" when there is none. */
    fun rawJson(context: Context): String = prefs(context).getString(KEY_TRIPS, null) ?: "[]"

    /** Replaces the trips with a backup's. Returns false, changing nothing, if it is not a trip list. */
    fun restore(context: Context, raw: String): Boolean {
        if (runCatching { JSONArray(raw) }.isFailure) return false
        prefs(context).edit { putString(KEY_TRIPS, raw) }
        return true
    }

    private fun parse(raw: String): List<Trip> {
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            Trip(
                id = item.optLong("id"),
                startMs = item.optLong("start"),
                endMs = item.optLong("end"),
                startKm = item.optNullableDouble("start_km"),
                endKm = item.optNullableDouble("end_km"),
                people = item.optInt("people", 1).coerceAtLeast(1),
                otherBaht = item.optDouble("other", 0.0),
                bahtPerKm = item.optNullableDouble("baht_per_km"),
            ).takeIf { it.startMs > 0 }
        }
    }

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null

    private fun write(context: Context, trips: List<Trip>) {
        val array = JSONArray()
        trips.sortedBy { it.startMs }.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("start", it.startMs)
                    .put("end", it.endMs)
                    .put("start_km", it.startKm ?: JSONObject.NULL)
                    .put("end_km", it.endKm ?: JSONObject.NULL)
                    .put("people", it.people)
                    .put("other", it.otherBaht)
                    .put("baht_per_km", it.bahtPerKm ?: JSONObject.NULL)
            )
        }
        prefs(context).edit { putString(KEY_TRIPS, array.toString()) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
