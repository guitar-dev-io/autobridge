package dev.autobridge.weather

import android.content.Context
import androidx.core.content.edit

/**
 * The one place the user picked for the Weather section. Following the rest of the codebase's
 * `*Store` convention: a plain `object`, one `SharedPreferences` file, `getX`/`setX`.
 */
object WeatherLocationStore {
    private const val PREFS_NAME = "autobridge_weather"
    private const val KEY_NAME = "location_name"
    private const val KEY_ADMIN1 = "location_admin1"
    private const val KEY_COUNTRY = "location_country"
    private const val KEY_LATITUDE = "location_latitude"
    private const val KEY_LONGITUDE = "location_longitude"

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The saved place, or null when the user has never picked one. */
    fun place(context: Context): WeatherPlace? {
        val p = prefs(context)
        val name = p.getString(KEY_NAME, null) ?: return null
        // 91f as a latitude is out of range and only ever the "unset" default below.
        val latitude = java.lang.Double.longBitsToDouble(p.getLong(KEY_LATITUDE, Long.MIN_VALUE))
        val longitude = java.lang.Double.longBitsToDouble(p.getLong(KEY_LONGITUDE, Long.MIN_VALUE))
        if (p.getLong(KEY_LATITUDE, Long.MIN_VALUE) == Long.MIN_VALUE) return null
        return WeatherPlace(
            name = name,
            admin1 = p.getString(KEY_ADMIN1, null),
            country = p.getString(KEY_COUNTRY, null),
            latitude = latitude,
            longitude = longitude
        )
    }

    fun setPlace(context: Context, place: WeatherPlace) {
        prefs(context).edit {
            putString(KEY_NAME, place.name)
            putString(KEY_ADMIN1, place.admin1)
            putString(KEY_COUNTRY, place.country)
            putLong(KEY_LATITUDE, java.lang.Double.doubleToLongBits(place.latitude))
            putLong(KEY_LONGITUDE, java.lang.Double.doubleToLongBits(place.longitude))
        }
    }

    fun clear(context: Context) {
        prefs(context).edit { clear() }
    }
}
