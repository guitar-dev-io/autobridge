package dev.autobridge.trip

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import dev.autobridge.logging.StructuredLog
import dev.autobridge.settings.AppPreferences

/**
 * Measures the trip in progress with the phone's GPS ([GpsOdometer]), so a trip has a distance
 * on cars that report no odometer.
 *
 * Gated like [dev.autobridge.speed.GpsSpeedSource]: [AppPreferences.tripGps] is the user's consent
 * for this purpose (off by default), and location permission alone never turns it on.
 *
 * What is stored, on this phone only: the running distance and the last accepted point, so a
 * restarted process carries on. Both are deleted when the trip ends; the finished trip keeps the
 * distance alone. Nothing is logged but the distance and nothing is sent anywhere.
 *
 * Android stops sending fixes to an app in the background after a while, so the figure may miss
 * the curves of a stretch driven with another app on the screen; [GpsOdometer] bridges such a gap
 * with a straight line, which is why the distance is shown as an estimate.
 */
object TripGpsTracker {
    private const val TAG = "TRIP"
    private const val PREFS = "autobridge_trip_gps"
    private const val KEY_TRIP = "trip_id"
    private const val KEY_METERS = "meters"
    private const val KEY_LAT = "anchor_lat"
    private const val KEY_LON = "anchor_lon"
    private const val KEY_AT = "anchor_at"
    private const val MIN_TIME_MS = 5_000L
    private const val MIN_DISTANCE_M = 10f

    private var appContext: Context? = null
    private var listener: LocationListener? = null
    private var trackedId: Long? = null
    private var odometer: GpsOdometer? = null

    /** Consent, permission and a location provider that is on. */
    fun canTrack(context: Context): Boolean {
        if (!AppPreferences.tripGps(context)) return false
        if (!hasPermission(context)) return false
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        return runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** The GPS distance so far of trip [id], or null if it is not being measured. */
    @Synchronized
    fun liveKm(context: Context, id: Long): Double? {
        val prefs = prefs(context)
        if (prefs.getLong(KEY_TRIP, 0L) != id) return null
        return (odometer?.takeIf { trackedId == id }?.meters ?: prefs.getFloat(KEY_METERS, 0f).toDouble()) / 1000.0
    }

    /** Called by [TripStore.start]: measure the new trip from zero. */
    @Synchronized
    fun onTripStarted(context: Context, id: Long) {
        stopListening()
        prefs(context).edit { clear() }
        ensure(context)
    }

    /**
     * Called by [TripStore.finish] and [TripStore.delete]: stops measuring and forgets the stored
     * point. Returns the distance in km, or null if this trip was never measured.
     */
    @Synchronized
    fun onTripEnded(context: Context, id: Long): Double? {
        val prefs = prefs(context)
        val measured = prefs.getLong(KEY_TRIP, 0L) == id
        if (measured && trackedId == id && hasPermission(context)) {
            // The last stretch since the previous fix, if the phone has a newer one at hand.
            runCatching {
                @Suppress("MissingPermission") // Checked just above.
                context.getSystemService(LocationManager::class.java)
                    ?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?.let { feed(it) }
            }
        }
        val km = if (measured) liveKm(context, id) else null
        stopListening()
        prefs.edit { clear() }
        if (km != null) StructuredLog.i(TAG, "gps distance ${"%.1f".format(java.util.Locale.US, km)} km")
        return km
    }

    /**
     * Listens while a trip is in progress and tracking is allowed; stops otherwise. Safe to call
     * often: from the trip screens and when a car session starts, so a restarted process resumes.
     */
    @Synchronized
    fun ensure(context: Context) {
        val active = TripStore.active(context)
        if (active == null || !canTrack(context)) {
            stopListening()
            return
        }
        if (trackedId == active.id && listener != null) return
        stopListening()
        val app = context.applicationContext
        val prefs = prefs(app)
        val state = if (prefs.getLong(KEY_TRIP, 0L) == active.id) readState(prefs) else GpsOdometer.State()
        appContext = app
        trackedId = active.id
        odometer = GpsOdometer(state)
        save()
        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) = synchronized(this@TripGpsTracker) { feed(location) }

            @Deprecated("Deprecated in API 29, kept for older providers")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        val started = runCatching {
            @Suppress("MissingPermission") // canTrack() checked the permission.
            app.getSystemService(LocationManager::class.java)?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, MIN_TIME_MS, MIN_DISTANCE_M, locationListener, Looper.getMainLooper()
            ) != null
        }.getOrDefault(false)
        if (started) {
            listener = locationListener
            StructuredLog.i(TAG, "measuring trip by gps")
        } else {
            StructuredLog.w(TAG, "gps updates unavailable")
            trackedId = null
            odometer = null
        }
    }

    private fun feed(location: Location) {
        val odo = odometer ?: return
        val before = odo.state
        odo.onFix(
            GpsOdometer.Fix(
                lat = location.latitude,
                lon = location.longitude,
                atMs = location.time,
                accuracyM = if (location.hasAccuracy()) location.accuracy else null,
            )
        )
        if (odo.state != before) save()
    }

    private fun stopListening() {
        val old = listener
        listener = null
        if (old != null) runCatching { appContext?.getSystemService(LocationManager::class.java)?.removeUpdates(old) }
        trackedId = null
        odometer = null
    }

    private fun save() {
        val context = appContext ?: return
        val id = trackedId ?: return
        val state = odometer?.state ?: return
        prefs(context).edit {
            putLong(KEY_TRIP, id)
            putFloat(KEY_METERS, state.meters.toFloat())
            if (state.anchorLat != null && state.anchorLon != null) {
                putString(KEY_LAT, state.anchorLat.toString())
                putString(KEY_LON, state.anchorLon.toString())
                putLong(KEY_AT, state.anchorMs)
            }
        }
    }

    private fun readState(prefs: android.content.SharedPreferences) = GpsOdometer.State(
        meters = prefs.getFloat(KEY_METERS, 0f).toDouble(),
        anchorLat = prefs.getString(KEY_LAT, null)?.toDoubleOrNull(),
        anchorLon = prefs.getString(KEY_LON, null)?.toDoubleOrNull(),
        anchorMs = prefs.getLong(KEY_AT, 0L),
    )

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
