package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import dev.autobridge.browser.BrowserDownloads
import dev.autobridge.core.state.RecentActivityStore

/**
 * Files the browser handed to Android's download manager, with each entry's live status. Paginated
 * the same way as [CarBrowserBookmarksScreen] so the car template stays inside its row limit.
 *
 * Opening a downloaded file is intentionally not offered here: the car surface has no document
 * viewer, and the notification the download manager already posts is the supported way to reach it
 * on the phone.
 */
class CarBrowserDownloadsScreen(carContext: CarContext) : Screen(carContext) {
    private var page = 0

    override fun onGetTemplate(): Template {
        val items = ItemList.Builder()
            .setNoItemsMessage("Files you download will show up here")
        val downloads = BrowserDownloads.list(carContext)
        page = page.coerceAtMost((downloads.size - 1).coerceAtLeast(0) / 4)
        if (page > 0) {
            items.addItem(
                Row.Builder().setTitle("Previous")
                    .setOnClickListener { page--; invalidate() }.build()
            )
        }
        downloads.drop(page * 4).take(4).forEach { entry ->
            val age = RecentActivityStore.relativeAge(entry.timestampMs)
            val status = BrowserDownloads.status(carContext, entry.id)
            items.addItem(
                Row.Builder()
                    .setTitle(entry.fileName)
                    .addText(listOfNotNull(status, age.takeIf { it.isNotBlank() }).joinToString("  •  "))
                    .build()
            )
        }
        if ((page + 1) * 4 < downloads.size) {
            items.addItem(
                Row.Builder().setTitle("More")
                    .setOnClickListener { page++; invalidate() }.build()
            )
        }
        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle("Downloads")
                    .setStartHeaderAction(Action.BACK)
                    .addEndHeaderAction(
                        Action.Builder()
                            .setIcon(CarIcons.of(carContext, CarIcons.CLEAR))
                            .setOnClickListener {
                                BrowserDownloads.clear(carContext)
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
