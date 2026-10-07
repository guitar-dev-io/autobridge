package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import dev.autobridge.R
import dev.autobridge.maintenance.MaintenanceStore
import dev.autobridge.trip.TripActivity
import dev.autobridge.trip.TripStore
import dev.autobridge.trip.TripText

/**
 * The trip so far, or the last one, with one button to start or end it using the car's odometer.
 * The party size is the last trip's and tolls or parking are added on the phone
 * ([TripActivity]) — typing is locked on the car while driving.
 */
class CarTripScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val trips = TripStore.all(carContext)
        val active = trips.firstOrNull { it.active }
        val odometer = MaintenanceStore.currentOdometer(carContext)
        val now = System.currentTimeMillis()
        val pane = Pane.Builder()

        val shown = active ?: trips.firstOrNull()
        if (shown == null) {
            pane.addRow(Row.Builder().setTitle(carContext.getString(R.string.trip_empty_title)).addText(carContext.getString(R.string.trip_car_empty)).build())
        } else {
            val currentKm = odometer.takeIf { shown.active }
            val bahtPerKm = if (shown.active) TripActivity.currentBahtPerKm(carContext) else shown.bahtPerKm
            pane.addRow(
                Row.Builder()
                    .setTitle(carContext.getString(if (shown.active) R.string.trip_in_progress else R.string.trip_last, shown.people))
                    .addText(TripText.summary(carContext, shown, currentKm, now))
                    .build()
            )
            TripText.cost(carContext, shown, currentKm, bahtPerKm)?.let {
                pane.addRow(Row.Builder().setTitle(it).build())
            }
        }

        // The car's own reading is what lets the car start a trip; without one, the phone does it.
        if (active != null || odometer != null) {
            pane.addAction(
                Action.Builder()
                    .setTitle(carContext.getString(if (active != null) R.string.trip_end else R.string.trip_start))
                    .setOnClickListener {
                        if (active != null) {
                            TripStore.finish(carContext, active.id, odometer, 0.0, TripActivity.currentBahtPerKm(carContext))
                        } else {
                            TripStore.start(carContext, odometer, TripStore.lastPeople(carContext))
                        }
                        invalidate()
                    }
                    .build()
            )
        }
        return PaneTemplate.Builder(pane.build())
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.trip_title))
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .build()
    }
}
