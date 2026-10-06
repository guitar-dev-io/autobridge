package dev.autobridge.car

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import dev.autobridge.R
import dev.autobridge.parking.ParkingLocator
import dev.autobridge.parking.ParkingStore
import java.text.DateFormat
import java.util.Date

/**
 * Where the car is parked, on the car: one button to mark the spot (from a fresh fix, only when
 * the button is pressed) and one to navigate back to it. The note that helps find the car among
 * others is typed on the phone ([dev.autobridge.parking.ParkingActivity]).
 */
class CarParkingScreen(carContext: CarContext) : Screen(carContext) {
    private var finding = false

    override fun onGetTemplate(): Template {
        val spot = ParkingStore.get(carContext)
        val pane = Pane.Builder()
        pane.addRow(
            if (spot == null) {
                Row.Builder()
                    .setTitle(carContext.getString(R.string.parking_empty_title))
                    .addText(carContext.getString(R.string.parking_car_empty))
                    .build()
            } else {
                Row.Builder()
                    .setTitle(spot.note.ifBlank { carContext.getString(R.string.parking_no_note) })
                    .addText(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(spot.savedMs)))
                    // The picture itself is for the phone: not something to look at while driving.
                    .apply { if (ParkingStore.hasPhoto(carContext)) addText(carContext.getString(R.string.parking_photo_on_phone)) }
                    .build()
            }
        )
        pane.addAction(
            Action.Builder()
                .setTitle(carContext.getString(if (finding) R.string.parking_finding else R.string.parking_mark_here))
                .setOnClickListener { if (!finding) markHere() }
                .build()
        )
        if (spot != null) {
            pane.addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.parking_navigate))
                    .setOnClickListener {
                        val started = runCatching {
                            carContext.startCarApp(Intent(CarContext.ACTION_NAVIGATE, Uri.parse(spot.navigationUri())))
                            true
                        }.getOrDefault(false)
                        if (!started) toast(R.string.car_browser_no_external)
                    }
                    .build()
            )
        }
        return PaneTemplate.Builder(pane.build())
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.parking_title))
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .build()
    }

    private fun markHere() {
        if (!ParkingLocator.hasPermission(carContext)) {
            carContext.requestPermissions(listOf(Manifest.permission.ACCESS_FINE_LOCATION)) { granted, _ ->
                if (Manifest.permission.ACCESS_FINE_LOCATION in granted) markHere() else toast(R.string.parking_no_fix)
            }
            return
        }
        finding = true
        invalidate()
        ParkingLocator.fix(carContext) { location ->
            finding = false
            if (location != null && ParkingStore.save(carContext, location.latitude, location.longitude)) {
                toast(R.string.parking_saved)
            } else {
                toast(R.string.parking_no_fix)
            }
            invalidate()
        }
    }

    private fun toast(resource: Int) {
        CarToast.makeText(carContext, carContext.getString(resource), CarToast.LENGTH_LONG).show()
    }
}
