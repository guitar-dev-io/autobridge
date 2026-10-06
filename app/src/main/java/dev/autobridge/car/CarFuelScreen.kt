package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.R
import dev.autobridge.fuel.CarVehicleData
import dev.autobridge.fuel.FuelLogStore
import dev.autobridge.fuel.FuelStats
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import kotlin.math.roundToLong

/**
 * The fuel log on the car: the summary only, read at a glance. Fill-ups are entered on the phone
 * ([dev.autobridge.fuel.FuelLogActivity]); typing is locked on the car while driving, and a log is
 * kept at the pump anyway.
 *
 * It is also where the car's odometer and fuel level are switched on: Android Auto asks for those
 * permissions on the car, and once granted [CarVehicleData] records the odometer so the phone can
 * fill it in for the next fill-up.
 */
class CarFuelScreen(carContext: CarContext) : Screen(carContext) {
    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                CarVehicleData.start(carContext)
            }
        })
    }

    override fun onGetTemplate(): Template {
        val ev = FuelLogStore.isEv(carContext)
        val entries = FuelLogStore.all(carContext)
        val summary = FuelStats.summarize(entries)
        val pane = Pane.Builder()
        pane.addRow(
            Row.Builder()
                .setTitle(
                    summary.averageKmPerLiter?.let {
                        carContext.getString(if (ev) R.string.fuel_km_per_kwh_value else R.string.fuel_km_per_liter_value, decimal(it))
                    } ?: carContext.getString(if (ev) R.string.fuel_average_pending_ev else R.string.fuel_average_pending)
                )
                .addText(carContext.getString(R.string.fuel_average_label))
                .build()
        )
        pane.addRow(
            Row.Builder()
                .setTitle(carContext.getString(R.string.fuel_this_month, money(summary.thisMonthBaht)))
                .apply { summary.bahtPerKm?.let { addText(carContext.getString(R.string.fuel_baht_per_km, decimal(it))) } }
                .build()
        )
        summary.last?.let { last ->
            pane.addRow(
                Row.Builder()
                    .setTitle(carContext.getString(if (ev) R.string.fuel_last_charge else R.string.fuel_last_fill, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(last.timeMs))))
                    .addText(
                        listOfNotNull(
                            carContext.getString(if (ev) R.string.fuel_row_title_ev else R.string.fuel_row_title, decimal(last.liters), money(last.totalBaht)),
                            last.fuelType.takeIf { it.isNotBlank() },
                        ).joinToString(" · ")
                    )
                    .build()
            )
        }
        val level = if (ev) CarVehicleData.batteryPercent else CarVehicleData.fuelPercent
        val range = CarVehicleData.rangeKm
        if (level != null || range != null) {
            pane.addRow(
                Row.Builder()
                    .setTitle(level?.let { carContext.getString(if (ev) R.string.fuel_car_battery else R.string.fuel_car_level, it.roundToLong()) } ?: "—")
                    .apply { range?.let { addText(carContext.getString(R.string.fuel_car_range, it.roundToLong())) } }
                    .build()
            )
        }
        if (!CarVehicleData.hasPermissions(carContext)) {
            pane.addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.fuel_allow_car_data))
                    .setOnClickListener {
                        carContext.requestPermissions(CarVehicleData.PERMISSIONS) { _, _ ->
                            CarVehicleData.start(carContext)
                            invalidate()
                        }
                    }
                    .build()
            )
        }
        return PaneTemplate.Builder(pane.build())
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.fuel_title))
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .build()
    }

    private fun decimal(value: Double): String =
        NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2; minimumFractionDigits = 1 }.format(value)

    private fun money(value: Double): String =
        NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2; minimumFractionDigits = 0 }.format(value)
}
