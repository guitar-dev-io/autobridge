package dev.autobridge.speed

import android.content.pm.PackageManager
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.Speed

/**
 * Reads vehicle speed from the Android Auto Car Hardware API. Prefers [Speed.displaySpeedMetersPerSecond]
 * (meant to match the car's own speedometer) and falls back to [Speed.rawSpeedMetersPerSecond].
 * Requires the CAR_SPEED permission and Car App API level 3+. Not every head unit reports speed, so
 * the CarValue status is checked before a value is used; otherwise an invalid sample is emitted.
 *
 * This is a DISPLAY source only. It is deliberately separate from [dev.autobridge.safety.SpeedGate]
 * and does not touch the safety/parking machinery.
 */
class CarSpeedSource(private val carContext: CarContext) : SpeedSource {
    override val origin: SpeedOrigin = SpeedOrigin.CAR

    private companion object {
        const val TAG = "AutoBridgeCarSpeed"
        const val CAR_SPEED_PERMISSION = "com.google.android.gms.permission.CAR_SPEED"
        const val MPS_TO_KMH = 3.6f
    }

    private val carInfo = runCatching {
        carContext.getCarService(CarHardwareManager::class.java).carInfo
    }.getOrNull()

    private var listener: OnCarDataAvailableListener<Speed>? = null

    override fun isAvailable(): Boolean =
        carInfo != null &&
            carContext.checkSelfPermission(CAR_SPEED_PERMISSION) == PackageManager.PERMISSION_GRANTED

    override fun start(onSample: (SpeedSample) -> Unit) {
        val info = carInfo ?: return
        if (!isAvailable()) return
        stop()
        val subscription = OnCarDataAvailableListener<Speed> { speed ->
            val display = speed.displaySpeedMetersPerSecond
            val raw = speed.rawSpeedMetersPerSecond
            val chosen: CarValue<Float> = if (display.status == CarValue.STATUS_SUCCESS) display else raw
            if (chosen.status == CarValue.STATUS_SUCCESS) {
                val mps = chosen.value ?: 0f
                onSample(SpeedSample(mps * MPS_TO_KMH, SpeedOrigin.CAR, valid = true))
            } else {
                onSample(SpeedSample(0f, SpeedOrigin.CAR, valid = false))
            }
        }
        listener = subscription
        runCatching {
            info.addSpeedListener(carContext.mainExecutor, subscription)
            Log.i(TAG, "Car speed listener subscribed")
        }.onFailure {
            Log.w(TAG, "Unable to subscribe to car speed", it)
            stop()
        }
    }

    override fun stop() {
        val old = listener ?: return
        listener = null
        runCatching { carInfo?.removeSpeedListener(old) }
    }
}
