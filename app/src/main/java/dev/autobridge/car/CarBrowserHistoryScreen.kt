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
import dev.autobridge.browser.BrowserDisplayUrl
import dev.autobridge.core.state.RecentActivityStore
import dev.autobridge.entertainment.WebHistoryStore

/** Returns a selection to the browser, paginated the same way as [CarBrowserBookmarksScreen]. */
class CarBrowserHistoryScreen(carContext: CarContext) : Screen(carContext) {
    private var page = 0

    override fun onGetTemplate(): Template {
        val items = ItemList.Builder().setNoItemsMessage(carContext.getString(R.string.car_browser_history_empty))
        val visited = WebHistoryStore.list(carContext)
        page = page.coerceAtMost((visited.size - 1).coerceAtLeast(0) / 4)
        if (page > 0) {
            items.addItem(Row.Builder().setTitle(carContext.getString(R.string.car_browser_history_previous))
                .setOnClickListener { page--; invalidate() }.build())
        }
        visited.drop(page * 4).take(4).forEach { entry ->
            val age = RecentActivityStore.relativeAge(entry.timestampMs)
            items.addItem(
                Row.Builder()
                    .setTitle(entry.title)
                    .addText(
                        BrowserDisplayUrl.compact(entry.url).let {
                            if (age.isBlank()) it else "$it  •  $age"
                        }
                    )
                    .setOnClickListener {
                        setResult(entry.url)
                        screenManager.pop()
                    }
                    .build()
            )
        }
        if ((page + 1) * 4 < visited.size) {
            items.addItem(Row.Builder().setTitle(carContext.getString(R.string.car_browser_history_more))
                .setOnClickListener { page++; invalidate() }.build())
        }
        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.car_browser_history_title))
                    .setStartHeaderAction(Action.BACK)
                    .addEndHeaderAction(
                        Action.Builder()
                            .setIcon(CarIcons.of(carContext, CarIcons.CLEAR))
                            .setOnClickListener {
                                WebHistoryStore.clear(carContext)
                                page = 0
                                invalidate()
                            }
                            .build()
                    )
                    .build()
            )
            .setSingleList(items.build())
            .build()
    }
}
