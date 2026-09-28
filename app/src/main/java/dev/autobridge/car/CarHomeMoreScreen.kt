package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import dev.autobridge.core.state.UiModeStore

/** Secondary destinations kept one tap away from the compact home grid. */
class CarHomeMoreScreen(carContext: CarContext, private val requestSafety: () -> Unit) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val items = ItemList.Builder()
        fun item(title: String, action: () -> Unit) {
            items.addItem(Row.Builder().setTitle(title).setOnClickListener { action() }.build())
        }
        // Media Center is reached from here rather than the home grid, which now leads with the
        // content sections shared with the phone launcher.
        item("Media Center") { screenManager.push(CarMediaCenterScreen(carContext)) }
        item("Manage bookmarks") { screenManager.push(CarBookmarksScreen(carContext)) }
        item("Agent") { screenManager.push(CarAgentScreen(carContext)) }
        item("Recent") { screenManager.push(CarRecentScreen(carContext)) }
        item("Driving") {
            UiModeStore.setDriving(true)
            screenManager.push(CarDrivingModeScreen(carContext))
        }
        item("Settings") { screenManager.push(CarSettingsScreen(carContext, requestSafety)) }
        val template = ListTemplate.Builder().setSingleList(items.build())
        if (carContext.carAppApiLevel >= 7) {
            template.setHeader(Header.Builder().setTitle("More").setStartHeaderAction(Action.BACK).build())
        } else {
            @Suppress("DEPRECATION")
            template.setTitle("More").setHeaderAction(Action.BACK)
        }
        return template.build()
    }
}
