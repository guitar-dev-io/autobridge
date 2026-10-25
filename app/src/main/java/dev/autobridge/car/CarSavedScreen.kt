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
import dev.autobridge.library.HomeSection

/**
 * Where the things a user kept or came back to are: Recent, Favorites and bookmarks. They were
 * three unrelated rows in [CarHomeMoreScreen], in three different groups; the screens behind them
 * are unchanged.
 */
class CarSavedScreen(
    carContext: CarContext,
    private val requestSafety: () -> Unit
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        fun row(titleRes: Int, action: () -> Unit) =
            Row.Builder().setTitle(carContext.getString(titleRes)).setOnClickListener(action).build()

        val list = ItemList.Builder()
            .addItem(row(R.string.car_more_recent) {
                CarNavigation.open(screenManager, "CarRecentScreen") { CarRecentScreen(carContext) }
            })
            .addItem(row(R.string.section_favorites) {
                CarHomeNavigator.open(carContext, screenManager, HomeSection.FAVORITES, requestSafety)
            })
            .addItem(row(R.string.car_more_bookmarks) {
                CarNavigation.open(screenManager, "CarBookmarksScreen") { CarBookmarksScreen(carContext) }
            })
            .build()

        val title = carContext.getString(R.string.car_saved_title)
        val template = ListTemplate.Builder().setSingleList(list)
        if (carContext.carAppApiLevel >= 7) {
            template.setHeader(Header.Builder().setTitle(title).setStartHeaderAction(Action.BACK).build())
        } else {
            @Suppress("DEPRECATION")
            template.setTitle(title).setHeaderAction(Action.BACK)
        }
        return template.build()
    }
}
