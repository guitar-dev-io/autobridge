package dev.autobridge.parking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.location.LocationManagerCompat

/**
 * One fresh fix for "I parked here", asked for when the driver taps the button and not otherwise.
 *
 * A last-known position can be hours old (whenever some app last looked), and marking the wrong
 * place would be worse than marking nothing, so a fix is accepted only if it is new: asked for now
 * on Android 11 and later, or no older than [MAX_AGE_MS] on older versions.
 */
object ParkingLocator {
    private const val MAX_AGE_MS = 2 * 60 * 1000L

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Calls [onFix] once, on the main thread, with a fresh position or null when there is none. */
    fun fix(context: Context, onFix: (Location?) -> Unit) {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null || !hasPermission(context)) {
            onFix(null)
            return
        }
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (provider == null) {
            onFix(null)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("MissingPermission") // Checked above.
            runCatching {
                LocationManagerCompat.getCurrentLocation(manager, provider, null, context.mainExecutor) { onFix(it) }
            }.onFailure { onFix(null) }
        } else {
            @Suppress("MissingPermission") // Checked above.
            val last = runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            onFix(last?.takeIf { System.currentTimeMillis() - it.time <= MAX_AGE_MS })
        }
    }
}
