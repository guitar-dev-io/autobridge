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
import dev.autobridge.iptv.RadioProvinceRepository
import dev.autobridge.iptv.ThaiProvinces

/**
 * The in-car province picker for the Local radio category: a [CarListPaging]-paged [ListTemplate]
 * of the curated [ThaiProvinces] labels. No free-text entry on car (a head unit cannot host a text
 * field here, and driver distraction rules out one anyway) — selecting a row persists the choice
 * through [RadioProvinceRepository] and pops back, letting the Local list re-query for that state.
 */
class CarRadioProvinceScreen(
    carContext: CarContext,
    private val page: Int = 0,
    private val onChosen: () -> Unit
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val provinces = ThaiProvinces.all
        val paged = CarListPaging.page(carContext, provinces, page)
        val list = ItemList.Builder()
        paged.items.forEach { province ->
            list.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(province.labelRes))
                    .setBrowsable(true)
                    .setOnClickListener {
                        RadioProvinceRepository.setSelected(carContext, province.state)
                        screenManager.pop()
                        onChosen()
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
                        screenManager.push(CarRadioProvinceScreen(carContext, page + 1, onChosen))
                    }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.radio_local_pick_title))
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .setSingleList(list.build())
            .build()
    }
}
