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
import dev.autobridge.core.state.UiModeStore
import dev.autobridge.library.HomeSection

/**
 * The car-side twin of the phone's My Car page: the things about the vehicle and the trip that are
 * looked at now and then (fuel, maintenance, costs, parking, the emergency card, weather, driving
 * mode). They used to be seven rows in [CarHomeMoreScreen], most of which a driver never opens;
 * behind one row they stop pushing the frequent entries down the host's short list.
 */
class CarMyCarScreen(
    carContext: CarContext,
    private val requestSafety: () -> Unit
) : Screen(carContext) {

    private data class Entry(val titleRes: Int?, val title: String?, val action: () -> Unit)

    private fun entry(titleRes: Int, action: () -> Unit) = Entry(titleRes, null, action)

    private fun entries(): List<Entry> = listOf(
        entry(R.string.car_more_fuel) {
            CarNavigation.open(screenManager, "CarFuelScreen") { CarFuelScreen(carContext) }
        },
        entry(R.string.maint_title) {
            CarNavigation.open(screenManager, "CarMaintenanceScreen") { CarMaintenanceScreen(carContext) }
        },
        entry(R.string.costs_title) {
            CarNavigation.open(screenManager, "CarCostsScreen") { CarCostsScreen(carContext) }
        },
        entry(R.string.parking_title) {
            CarNavigation.open(screenManager, "CarParkingScreen") { CarParkingScreen(carContext) }
        },
        entry(R.string.emergency_title) {
            CarNavigation.open(screenManager, "CarEmergencyScreen") { CarEmergencyScreen(carContext) }
        },
        Entry(null, HomeSection.WEATHER.title(carContext)) {
            CarHomeNavigator.open(carContext, screenManager, HomeSection.WEATHER, requestSafety)
        },
        entry(R.string.car_more_driving) {
            UiModeStore.setDriving(true)
            screenManager.push(CarDrivingModeScreen(carContext))
        }
    )

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        entries().forEach { e ->
            val title = e.title ?: carContext.getString(e.titleRes!!)
            list.addItem(Row.Builder().setTitle(title).setOnClickListener { e.action() }.build())
        }
        val title = carContext.getString(R.string.car_my_car_title)
        val template = ListTemplate.Builder().setSingleList(list.build())
        if (carContext.carAppApiLevel >= 7) {
            template.setHeader(Header.Builder().setTitle(title).setStartHeaderAction(Action.BACK).build())
        } else {
            @Suppress("DEPRECATION")
            template.setTitle(title).setHeaderAction(Action.BACK)
        }
        return template.build()
    }
}
