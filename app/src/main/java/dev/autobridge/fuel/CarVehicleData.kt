package dev.autobridge.fuel

import android.content.Context
import android.content.pm.PackageManager
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.EnergyLevel
import androidx.car.app.hardware.info.Mileage
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import dev.autobridge.logging.StructuredLog

/**
 * What the car itself reports about distance and fuel, for the fuel log.
 *
 * Android Auto passes on a car's odometer, fuel level and remaining range when the car sends them
 * and the user has allowed it ([PERMISSIONS]). Many cars send none, some only the fuel level; every
 * value here is therefore optional, and the log works with nothing from the car at all. A car's
 * own "average consumption" display is not one of the values Android Auto passes on, so the log
 * works its average out from fill-ups instead.
 *
 * The last odometer reading is kept on disk with its time, so the phone can suggest it when a
 * fill-up is logged after the car has disconnected — at the pump, typically.
 */
object CarVehicleData {
    private const val TAG = "FUEL"
    private const val PREFS = "autobridge_vehicle_data"
    private const val KEY_ODOMETER = "odometer_km"
    private const val KEY_ODOMETER_AT = "odometer_at"

    /** Reading the odometer and the fuel level; both are runtime permissions on Android Auto. */
    val PERMISSIONS = listOf(
        "com.google.android.gms.permission.CAR_MILEAGE",
        "com.google.android.gms.permission.CAR_FUEL",
    )

    @Volatile var fuelPercent: Float? = null
        private set

    /** An EV's battery charge, from the same energy report. */
    @Volatile var batteryPercent: Float? = null
        private set

    @Volatile var rangeKm: Float? = null
        private set

    private var appContext: Context? = null
    private var listening: CarContext? = null

    private val mileageListener = OnCarDataAvailableListener<Mileage> { mileage ->
        mileage.odometerMeters.takeIf { it.status == CarValue.STATUS_SUCCESS }?.value?.let { meters ->
            val km = meters / 1000.0
            appContext?.let { context ->
                prefs(context).edit {
                    putFloat(KEY_ODOMETER, km.toFloat())
                    putLong(KEY_ODOMETER_AT, System.currentTimeMillis())
                }
            }
        }
    }

    private val energyListener = OnCarDataAvailableListener<EnergyLevel> { level ->
        fuelPercent = level.fuelPercent.takeIf { it.status == CarValue.STATUS_SUCCESS }?.value
        batteryPercent = level.batteryPercent.takeIf { it.status == CarValue.STATUS_SUCCESS }?.value
        rangeKm = level.rangeRemainingMeters.takeIf { it.status == CarValue.STATUS_SUCCESS }?.value?.div(1000f)
    }

    fun hasPermissions(context: Context): Boolean =
        PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /**
     * Starts listening on [carContext]'s car. Safe to call again; a car that does not report, or
     * permissions not granted, simply leave the values empty.
     */
    fun start(carContext: CarContext) {
        appContext = carContext.applicationContext
        if (listening === carContext || !hasPermissions(carContext)) return
        stop()
        runCatching {
            val info = carContext.getCarService(CarHardwareManager::class.java).carInfo
            info.addMileageListener(carContext.mainExecutor, mileageListener)
            info.addEnergyLevelListener(carContext.mainExecutor, energyListener)
            listening = carContext
            StructuredLog.i(TAG, "listening for odometer and fuel level")
        }.onFailure { StructuredLog.w(TAG, "car data unavailable: ${it.message}") }
    }

    fun stop() {
        val carContext = listening ?: return
        listening = null
        runCatching {
            val info = carContext.getCarService(CarHardwareManager::class.java).carInfo
            info.removeMileageListener(mileageListener)
            info.removeEnergyLevelListener(energyListener)
        }
    }

    /** The last odometer the car reported, and when, or null if it never has. */
    fun lastOdometer(context: Context): Pair<Double, Long>? {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_ODOMETER)) return null
        return prefs.getFloat(KEY_ODOMETER, 0f).toDouble() to prefs.getLong(KEY_ODOMETER_AT, 0L)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
