package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Template
import dev.autobridge.library.StreamingLinks

/**
 * The Streaming home tile: the shared [StreamingLinks] catalog as a grouped list.
 *
 * Each row loads its site into the same session-scoped car browser the YouTube tile uses, so
 * playback, audio focus, cookies and the parked gate behave exactly as for any other page.
 */
class CarStreamingScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val template = ListTemplate.Builder()
            .setHeader(Header.Builder().setTitle("Streaming").setStartHeaderAction(Action.BACK).build())
        // Sections share the host's row limit; keep the total within it.
        var budget = CarListPaging.limit(carContext)
        StreamingLinks.grouped().forEach { (group, links) ->
            if (budget <= 0) return@forEach
            val list = ItemList.Builder()
            links.take(budget).forEach { link ->
                list.addItem(
                    Row.Builder()
                        .setTitle(link.title)
                        .setBrowsable(true)
                        .setOnClickListener { open(link.url) }
                        .build()
                )
            }
            budget -= links.size
            template.addSectionedList(SectionedItemList.create(list.build(), group.title))
        }
        return template.build()
    }

    private fun open(url: String) {
        dev.autobridge.browser.CarBrowserRuntime.renderer(carContext).load(url)
        CarNavigation.open(screenManager, "CarBrowserScreen") { CarBrowserScreen(carContext) }
    }
}
