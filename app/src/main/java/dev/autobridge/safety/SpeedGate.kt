package dev.autobridge.safety

import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.Speed

class SpeedGate(private val carContext: CarContext, private val onMoving: () -> Unit) : VehicleStateProvider {
    companion object {
        const val CAR_SPEED_PERMISSION = "com.google.android.gms.permission.CAR_SPEED"
        private const val TAG = "AutoBridgeSpeedGate"
        private const val TIMEOUT_MS = 3_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val carInfo = runCatching {
        carContext.getCarService(CarHardwareManager::class.java).carInfo
    }.getOrNull()
    private var active = false
    private var listener: OnCarDataAvailableListener<Speed>? = null
    private val expire = Runnable { ParkingStateStore.update(ParkingStateStore.State.UNKNOWN) }

    override val name: String = "CAR_SPEED / CarHardwareManager"

    override fun hasPermission(): Boolean =
        carContext.checkSelfPermission(CAR_SPEED_PERMISSION) == PackageManager.PERMISSION_GRANTED

    override fun start() {
        active = true
        val permissionGranted = hasPermission()
        Log.i(TAG, "Starting speed gate permissionGranted=$permissionGranted carInfoAvailable=${carInfo != null}")
        if (listener != null && permissionGranted) return
        unsubscribe()
        if (!permissionGranted) {
            Log.i(TAG, "Speed gate waiting for CAR_SPEED permission")
            return
        }
        val info = carInfo
        if (info == null) {
            Log.w(TAG, "Speed gate unavailable: CarInfo service is null")
            return
        }
        val subscription = object : OnCarDataAvailableListener<Speed> {
            override fun onCarDataAvailable(speed: Speed) {
                // Removed subscriptions may still have callbacks queued on the main executor.
                if (!active || listener !== this) return
                handler.removeCallbacks(expire)
                val value = speed.rawSpeedMetersPerSecond
                Log.i(
                    TAG,
                    "Speed callback status=${value.status} value=${value.value} active=$active permission=${hasPermission()}"
                )
                val state = SpeedPolicy.classify(
                    if (hasPermission() && value.status == CarValue.STATUS_SUCCESS) value.value else null
                )
                val previous = ParkingStateStore.state
                ParkingStateStore.update(state)
                Log.i(TAG, "Speed classified as $state (previous=$previous)")
                if (state == ParkingStateStore.State.PARKED) handler.postDelayed(expire, TIMEOUT_MS)
                if (state == ParkingStateStore.State.MOVING && previous != state) onMoving()
            }
        }
        listener = subscription
        runCatching {
            info.addSpeedListener(carContext.mainExecutor, subscription)
            Log.i(TAG, "Speed listener subscribed")
        }.onFailure {
            Log.w(TAG, "Unable to subscribe to speed", it)
            unsubscribe()
        }
    }

    private fun unsubscribe() {
        val old = listener
        listener = null
        handler.removeCallbacks(expire)
        if (old != null) runCatching { carInfo?.removeSpeedListener(old) }
        ParkingStateStore.update(ParkingStateStore.State.UNKNOWN)
    }

    override fun stop() {
        active = false
        unsubscribe()
    }

    /** Invoke from a ParkedOnlyOnClickListener. */
    override fun requestPermission(onResult: (Boolean) -> Unit) {
        carContext.requestPermissions(listOf(CAR_SPEED_PERMISSION), carContext.mainExecutor) { approved, _ ->
            if (!active) return@requestPermissions
            val granted = approved.contains(CAR_SPEED_PERMISSION) && hasPermission()
            if (granted) start() else unsubscribe()
            onResult(granted)
        }
    }
}
