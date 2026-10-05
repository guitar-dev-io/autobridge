package dev.autobridge.speed

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import dev.autobridge.settings.AppPreferences

/**
 * Phone-GPS speed fallback used when the Car Hardware API reports no usable speed. Uses
 * [Location.getSpeed] (m/s). If the fix is old or missing, an invalid sample is emitted so the
 * manager can fall through to another source.
 *
 * Two gates, both required, and in this order:
 *
 *  1. [AppPreferences.gpsSpeed] - the user turned GPS speed on in car settings, having read what
 *     location would be used for. Off by default.
 *  2. ACCESS_FINE_LOCATION granted at runtime.
 *
 * The preference is not a duplicate of the permission. The app can hold location permission for an
 * entirely different reason - a map page in the browser asked for it - and reading the user's
 * position for a second purpose on the strength of that grant is what Play's location policy (and
 * plain fairness) forbids. Without the opt-in this source reports itself unavailable and never
 * registers a listener.
 */
class GpsSpeedSource(private val context: Context) : SpeedSource {
    override val origin: SpeedOrigin = SpeedOrigin.GPS

    private companion object {
        const val TAG = "AutoBridgeGpsSpeed"
        const val MPS_TO_KMH = 3.6f
        const val MIN_TIME_MS = 1_000L
        const val MIN_DISTANCE_M = 0f
    }

    private val locationManager =
        context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private var listener: LocationListener? = null

    override fun isAvailable(): Boolean {
        if (!AppPreferences.gpsSpeed(context)) return false
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val providerEnabled = runCatching {
            locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                locationManager?.isProviderEnabled(LocationManager.FUSED_PROVIDER) == true
        }.getOrDefault(false)
        return granted && providerEnabled
    }

    override fun start(onSample: (SpeedSample) -> Unit) {
        val manager = locationManager ?: return
        if (!isAvailable()) return
        stop()
        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (location.hasSpeed()) {
                    onSample(SpeedSample(location.speed * MPS_TO_KMH, SpeedOrigin.GPS, valid = true))
                } else {
                    onSample(SpeedSample(0f, SpeedOrigin.GPS, valid = false))
                }
            }

            @Deprecated("Deprecated in API 29, kept for older providers")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        listener = locationListener
        runCatching {
            @Suppress("MissingPermission") // Guarded by isAvailable() above.
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_TIME_MS,
                MIN_DISTANCE_M,
                locationListener,
                context.mainLooper
            )
            Log.i(TAG, "GPS speed updates requested")
        }.onFailure {
            Log.w(TAG, "Unable to request GPS updates", it)
            stop()
        }
    }

    override fun stop() {
        val old = listener ?: return
        listener = null
        runCatching { locationManager?.removeUpdates(old) }
    }
}
