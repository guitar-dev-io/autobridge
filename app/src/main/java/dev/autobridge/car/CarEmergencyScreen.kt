package dev.autobridge.car

import android.content.Intent
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import dev.autobridge.R
import dev.autobridge.emergency.EmergencyStore

/**
 * The emergency card on the car: tap a number and the dialer opens with it filled in (the driver
 * presses call); a line that is not a number, like a policy number, is shown to be read out.
 * Entered on the phone ([dev.autobridge.emergency.EmergencyActivity]).
 */
class CarEmergencyScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val entries = EmergencyStore.all(carContext)
        val list = ItemList.Builder()
        if (entries.isEmpty()) {
            list.setNoItemsMessage(carContext.getString(R.string.emergency_car_empty))
        } else {
            entries.take(CarListPaging.limit(carContext)).forEach { entry ->
                val dial = entry.dialable
                val row = Row.Builder().setTitle(entry.label).addText(entry.value)
                if (dial != null) {
                    row.setBrowsable(true).setOnClickListener { dial(dial) }
                }
                list.addItem(row.build())
            }
        }
        return ListTemplate.Builder()
            .setSingleList(list.build())
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.emergency_title))
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .build()
    }

    private fun dial(number: String) {
        val started = runCatching {
            carContext.startCarApp(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
            true
        }.getOrDefault(false)
        if (!started) {
            CarToast.makeText(carContext, carContext.getString(R.string.emergency_cannot_dial), CarToast.LENGTH_LONG).show()
        }
    }
}
