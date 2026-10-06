package dev.autobridge.parking

import android.content.Context
import androidx.core.content.edit

/**
 * Where the car was last parked, on this phone only: one spot, the latest. Only what the driver
 * marks is kept; the phone's position is never tracked or stored otherwise.
 */
object ParkingStore {
    private const val PREFS = "autobridge_parking"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"
    private const val KEY_NOTE = "note"
    private const val KEY_TIME = "time"

    fun get(context: Context): ParkingSpot? {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_LAT) || !prefs.contains(KEY_LON)) return null
        return ParkingSpot(
            Double.fromBits(prefs.getLong(KEY_LAT, 0L)),
            Double.fromBits(prefs.getLong(KEY_LON, 0L)),
            prefs.getString(KEY_NOTE, "").orEmpty(),
            prefs.getLong(KEY_TIME, 0L),
        ).takeIf { ParkingSpot.isValid(it.lat, it.lon) }
    }

    /** Saves the spot; false when the position is not a real place (and nothing changes). */
    fun save(context: Context, lat: Double, lon: Double, note: String = "", nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!ParkingSpot.isValid(lat, lon)) return false
        prefs(context).edit {
            putLong(KEY_LAT, lat.toRawBits())
            putLong(KEY_LON, lon.toRawBits())
            putString(KEY_NOTE, note.trim())
            putLong(KEY_TIME, nowMs)
        }
        return true
    }

    fun setNote(context: Context, note: String) {
        if (get(context) != null) prefs(context).edit { putString(KEY_NOTE, note.trim()) }
    }

    fun clear(context: Context) = prefs(context).edit { clear() }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
