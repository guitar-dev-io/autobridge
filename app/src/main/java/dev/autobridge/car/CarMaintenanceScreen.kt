package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import dev.autobridge.R
import dev.autobridge.maintenance.DueState
import dev.autobridge.maintenance.MaintenanceStats
import dev.autobridge.maintenance.MaintenanceStore
import dev.autobridge.maintenance.MaintenanceText

/**
 * What the car is due for, most urgent first. Read-only: items are set up on the phone
 * ([dev.autobridge.maintenance.MaintenanceActivity]) since typing is locked on the car.
 */
class CarMaintenanceScreen(carContext: CarContext) : Screen(carContext) {
    private companion object {
        /** A car list is for glancing at; the rest are on the phone. */
        const val MAX_ROWS = 6
    }

    override fun onGetTemplate(): Template {
        val statuses = MaintenanceStats.ordered(
            MaintenanceStore.all(carContext), MaintenanceStore.currentOdometer(carContext), System.currentTimeMillis()
        )
        val list = ItemList.Builder()
        if (statuses.isEmpty()) {
            list.setNoItemsMessage(carContext.getString(R.string.maint_car_empty))
        } else {
            statuses.take(MAX_ROWS).forEach { status ->
                list.addItem(
                    Row.Builder()
                        .setTitle(
                            (if (status.state == DueState.OK) "" else MaintenanceText.badge(status.state) + " ") + status.item.name
                        )
                        .addText(MaintenanceText.standing(carContext, status))
                        .build()
                )
            }
        }
        return ListTemplate.Builder()
            .setSingleList(list.build())
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.maint_title))
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .build()
    }
}
