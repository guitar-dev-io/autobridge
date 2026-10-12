package dev.autobridge.parking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import dev.autobridge.parking.ParkingFixChoice.Candidate

/**
 * A position for "I parked here", asked for when the driver taps the button and not otherwise.
 *
 * Fast first: a position the phone already has from the last couple of minutes is used at once
 * when it is precise enough. Otherwise every source is asked together (fused, Wi-Fi/cell, GPS), the
 * first precise answer wins, and after [TIMEOUT_MS] the best usable one is kept, or none; the
 * screen then offers to save the spot without a position. [ParkingFixChoice] decides what counts.
 */
object ParkingLocator {
    const val TIMEOUT_MS = 8_000L

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Calls [onFix] once, on the main thread, with a usable position or null when there is none in time. */
    @Suppress("MissingPermission") // hasPermission() is checked first.
    fun fix(context: Context, onFix: (Location?) -> Unit) {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null || !hasPermission(context)) {
            onFix(null)
            return
        }
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }.filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            onFix(null)
            return
        }

        val readings = mutableListOf<Location>()
        providers.forEach { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull()?.let(readings::add) }
        val now = System.currentTimeMillis()
        readings.firstOrNull { ParkingFixChoice.isGood(it.candidate(), now) }?.let {
            onFix(it)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onFix(pickBest(readings))
            return
        }

        val main = Handler(Looper.getMainLooper())
        val cancel = CancellationSignal()
        var done = false
        var answered = 0
        fun finish(location: Location?) {
            if (done) return
            done = true
            cancel.cancel()
            main.removeCallbacksAndMessages(cancel)
            onFix(location)
        }
        main.postAtTime({ finish(pickBest(readings)) }, cancel, android.os.SystemClock.uptimeMillis() + TIMEOUT_MS)
        providers.forEach { provider ->
            runCatching {
                manager.getCurrentLocation(provider, cancel, context.mainExecutor) { location ->
                    answered++
                    if (location != null) {
                        readings += location
                        if (ParkingFixChoice.isGood(location.candidate(), System.currentTimeMillis())) finish(location)
                    }
                    if (answered == providers.size) finish(pickBest(readings))
                }
            }.onFailure {
                answered++
                if (answered == providers.size) finish(pickBest(readings))
            }
        }
    }

    private fun pickBest(readings: List<Location>): Location? {
        val best = ParkingFixChoice.best(readings.map { it.candidate() }, System.currentTimeMillis()) ?: return null
        return readings.firstOrNull { it.candidate() == best }
    }

    private fun Location.candidate() = Candidate(latitude, longitude, if (hasAccuracy()) accuracy else null, time)
}
