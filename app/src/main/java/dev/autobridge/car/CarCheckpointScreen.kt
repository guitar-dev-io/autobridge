package dev.autobridge.car

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.R
import dev.autobridge.checkpoint.CheckpointMath
import dev.autobridge.checkpoint.CheckpointStore
import dev.autobridge.logging.StructuredLog
import java.text.NumberFormat

/**
 * How far the nearest of the driver's own checkpoints are, with a one-tap "mark here".
 *
 * The driver's location is read only while this screen is showing, only once they have allowed it
 * (here or on the phone), and is kept in memory just long enough to measure a distance: it is never
 * stored or sent. A toast says when a checkpoint is close; it is only shown while this screen is,
 * so the screen is meant to be left open on the drive.
 */
class CarCheckpointScreen(carContext: CarContext) : Screen(carContext) {
    private companion object {
        const val TAG = "CHECKPOINT"
        const val MIN_TIME_MS = 3_000L
        const val ALERT_M = 500.0
        /** Past this a checkpoint may alert again, so one passed and left behind does not stay quiet. */
        const val REARM_M = 1_500.0
        const val REFRESH_MS = 5_000L
    }

    private val locationManager = carContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private var fix: Location? = null
    private val alerted = mutableSetOf<Long>()
    private var lastRefresh = 0L

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            fix = location
            val points = CheckpointStore.all(carContext)
            points.forEach { point ->
                val meters = CheckpointMath.distanceM(location.latitude, location.longitude, point.lat, point.lon)
                if (meters <= ALERT_M && alerted.add(point.id)) {
                    CarToast.makeText(
                        carContext,
                        carContext.getString(R.string.checkpoint_close, point.name.ifBlank { carContext.getString(R.string.checkpoint_unnamed) }, meters.toLong()),
                        CarToast.LENGTH_LONG
                    ).show()
                } else if (meters > REARM_M) {
                    alerted.remove(point.id)
                }
            }
            // Template updates are rate limited; the distances only need to be fresh to the second or two.
            val now = System.currentTimeMillis()
            if (now - lastRefresh >= REFRESH_MS) {
                lastRefresh = now
                invalidate()
            }
        }

        @Deprecated("Deprecated in API 29, kept for older providers")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = startListening()
            override fun onStop(owner: LifecycleOwner) = stopListening()
        })
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun active() = CheckpointStore.locationAllowed(carContext) && hasPermission()

    private fun startListening() {
        if (!active()) return
        val manager = locationManager ?: return
        runCatching {
            @Suppress("MissingPermission") // Guarded by active() above.
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, MIN_TIME_MS, 0f, listener, carContext.mainLooper)
            StructuredLog.i(TAG, "reading location for the checkpoint screen")
        }.onFailure { StructuredLog.w(TAG, "location unavailable: ${it.message}") }
    }

    private fun stopListening() {
        runCatching { locationManager?.removeUpdates(listener) }
        fix = null
        alerted.clear()
    }

    override fun onGetTemplate(): Template {
        val pane = Pane.Builder()
        val points = CheckpointStore.all(carContext)
        val current = fix
        if (!active()) {
            pane.addRow(Row.Builder().setTitle(carContext.getString(R.string.checkpoint_allow_title)).addText(carContext.getString(R.string.checkpoint_privacy)).build())
            pane.addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.checkpoint_location_allow))
                    .setOnClickListener {
                        CheckpointStore.setLocationAllowed(carContext, true)
                        if (hasPermission()) {
                            startListening()
                            invalidate()
                        } else {
                            carContext.requestPermissions(listOf(Manifest.permission.ACCESS_FINE_LOCATION)) { _, _ ->
                                startListening()
                                invalidate()
                            }
                        }
                    }
                    .build()
            )
        } else if (points.isEmpty()) {
            pane.addRow(Row.Builder().setTitle(carContext.getString(R.string.checkpoint_empty_title)).addText(carContext.getString(R.string.checkpoint_car_empty)).build())
        } else if (current == null) {
            pane.addRow(Row.Builder().setTitle(carContext.getString(R.string.checkpoint_waiting_fix)).build())
        } else {
            val km = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1; minimumFractionDigits = 1 }
            CheckpointMath.nearest(points, current.latitude, current.longitude).forEach { near ->
                pane.addRow(
                    Row.Builder()
                        .setTitle(near.checkpoint.name.ifBlank { carContext.getString(R.string.checkpoint_unnamed) })
                        .addText(
                            if (near.meters < 1_000) carContext.getString(R.string.checkpoint_distance_m, near.meters.toLong())
                            else carContext.getString(R.string.checkpoint_distance_km, km.format(near.meters / 1_000))
                        )
                        .build()
                )
            }
        }
        if (active()) {
            pane.addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.checkpoint_mark_here))
                    .setOnClickListener {
                        val here = fix
                        if (here == null) {
                            CarToast.makeText(carContext, carContext.getString(R.string.checkpoint_no_fix), CarToast.LENGTH_LONG).show()
                        } else {
                            CheckpointStore.add(carContext, carContext.getString(R.string.checkpoint_default_name), here.latitude, here.longitude)
                            // Standing on it: no toast for the point just made.
                            CheckpointStore.all(carContext).firstOrNull()?.let { alerted.add(it.id) }
                            invalidate()
                        }
                    }
                    .build()
            )
        }
        return PaneTemplate.Builder(pane.build())
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.checkpoint_title))
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .build()
    }
}
