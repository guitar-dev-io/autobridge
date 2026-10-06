package dev.autobridge.car

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
import dev.autobridge.library.StreamingFavoritesStore
import dev.autobridge.library.StreamingLinks

/**
 * Favourite streaming sites on the car: tap a saved one to remove it, tap a suggestion from the
 * Streaming catalog to add it. Car App templates have no text field, so what is offered to add is
 * the catalog; any other address is added on the phone (Streaming > Add favorite).
 */
class CarStreamingFavoritesScreen(carContext: CarContext) : Screen(carContext) {
    private companion object {
        /** Saved and suggested rows share the host's list limit; this keeps the total within it. */
        const val SUGGESTED_ROWS = 6
    }

    override fun onGetTemplate(): Template {
        val saved = StreamingFavoritesStore.list(carContext)
        val savedUrls = saved.map { it.url }.toSet()
        val list = ItemList.Builder()
        var budget = CarListPaging.limit(carContext)

        saved.take(budget).forEach { favorite ->
            list.addItem(
                Row.Builder()
                    .setTitle(favorite.title)
                    .addText(carContext.getString(R.string.car_streaming_tap_to_remove, host(favorite.url)))
                    .setOnClickListener {
                        StreamingFavoritesStore.remove(carContext, favorite.url)
                        CarToast.makeText(carContext, carContext.getString(R.string.car_streaming_removed, favorite.title), CarToast.LENGTH_SHORT).show()
                        invalidate()
                    }
                    .build()
            )
            budget--
        }
        StreamingLinks.all.filterNot { it.url in savedUrls }.take(minOf(SUGGESTED_ROWS, budget.coerceAtLeast(0))).forEach { link ->
            list.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_streaming_add, link.title))
                    .addText(host(link.url))
                    .setOnClickListener {
                        StreamingFavoritesStore.add(carContext, link.title, link.url)
                        CarToast.makeText(carContext, carContext.getString(R.string.car_streaming_added, link.title), CarToast.LENGTH_SHORT).show()
                        invalidate()
                    }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setSingleList(list.build())
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.car_streaming_manage))
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .build()
    }

    private fun host(url: String): String = runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.") ?: url
}
