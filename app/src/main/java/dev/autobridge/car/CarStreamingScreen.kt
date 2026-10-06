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
import dev.autobridge.R
import dev.autobridge.library.StreamingFavoritesStore
import dev.autobridge.library.StreamingLinks

/**
 * The Streaming home tile: the shared [StreamingLinks] catalog as a grouped list.
 *
 * Each row loads its site into the same session-scoped car browser the YouTube tile uses, so
 * playback, audio focus, cookies and the parked gate behave exactly as for any other page.
 */
class CarStreamingScreen(carContext: CarContext) : Screen(carContext) {
    private companion object {
        const val MAX_FAVORITE_ROWS = 4
    }

    override fun onGetTemplate(): Template {
        val template = ListTemplate.Builder()
            .setHeader(Header.Builder()
                    .setTitle(carContext.getString(R.string.car_streaming_title))
                    .setStartHeaderAction(Action.BACK)
                    .build())
        // Sections share the host's row limit; keep the total within it.
        var budget = CarListPaging.limit(carContext)

        // The driver's own favourites first, and the way to change them. Kept to a few rows so the
        // catalog below is not squeezed out of the list.
        val favorites = StreamingFavoritesStore.list(carContext)
        val own = ItemList.Builder()
        favorites.take(MAX_FAVORITE_ROWS).forEach { favorite ->
            own.addItem(
                Row.Builder()
                    .setTitle(favorite.title)
                    .setBrowsable(true)
                    .setOnClickListener { open(favorite.url) }
                    .build()
            )
            budget--
        }
        own.addItem(
            Row.Builder()
                .setTitle(carContext.getString(R.string.car_streaming_manage))
                .setBrowsable(true)
                .setOnClickListener {
                    CarNavigation.open(screenManager, "CarStreamingFavoritesScreen") { CarStreamingFavoritesScreen(carContext) }
                }
                .build()
        )
        budget--
        template.addSectionedList(SectionedItemList.create(own.build(), carContext.getString(R.string.car_streaming_favorites)))

        StreamingLinks.grouped().forEach { (group, links) ->
            if (budget <= 0) return@forEach
            val list = ItemList.Builder()
            links.take(budget).forEach { link ->
                list.addItem(
                    Row.Builder()
                        .setTitle(link.title)
                        .apply {
                            if (StreamingLinks.mayNotPlay(link)) addText(carContext.getString(R.string.car_streaming_may_not_play))
                        }
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

    /** Same entry rule as the home tiles: a site already open is returned to, not reloaded. */
    private fun open(url: String) {
        val renderer = dev.autobridge.browser.CarBrowserRuntime.renderer(carContext)
        if (!dev.autobridge.browser.BrowserSiteEntry.resumes(renderer.livePageUrl, url)) {
            renderer.load(url)
        }
        CarNavigation.open(screenManager, "CarBrowserScreen") { CarBrowserScreen(carContext) }
    }
}
