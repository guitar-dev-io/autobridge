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
import dev.autobridge.iptv.RadioCategory

/**
 * The five [RadioCategory] rows, reached from the "Browse categories" row on the radio landing.
 *
 * Kept its own screen so the landing stays within the host's list limit (the landing adds a single
 * browsable row, not five). The five rows are trivially within the limit, but they are still paged
 * defensively with [CarListPaging] like every other category screen, so an unusually small host
 * limit never drops a row. Each row pushes [CarRadioCategoryScreen] for its category.
 */
class CarRadioCategoriesScreen(carContext: CarContext, private val page: Int = 0) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val categories = RadioCategory.values().toList()
        val paged = CarListPaging.page(carContext, categories, page)
        val list = ItemList.Builder()
        paged.items.forEach { category ->
            list.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(category.labelRes))
                    .setBrowsable(true)
                    .setOnClickListener {
                        screenManager.push(CarRadioCategoryScreen(carContext, category))
                    }
                    .build()
            )
        }
        if (paged.hasMore) {
            list.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_iptv_show_more))
                    .setBrowsable(true)
                    .setOnClickListener {
                        screenManager.push(CarRadioCategoriesScreen(carContext, page + 1))
                    }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.car_iptv_radio))
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .setSingleList(list.build())
            .build()
    }
}
