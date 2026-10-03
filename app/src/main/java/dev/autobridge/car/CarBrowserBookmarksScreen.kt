package dev.autobridge.car

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
import dev.autobridge.browser.BrowserDisplayUrl
import dev.autobridge.entertainment.WebBookmarkStore

/**
 * Returns a selection to the existing browser, retaining its page history. The "Manage" header
 * action flips tapping a row from "open" to "remove", the same open/long-press-to-remove split
 * a Fermata-style bookmarks menu exposes without needing a second screen.
 */
class CarBrowserBookmarksScreen(carContext: CarContext) : Screen(carContext) {
    private var page = 0
    private var manageMode = false

    override fun onGetTemplate(): Template {
        val items = ItemList.Builder().setNoItemsMessage(carContext.getString(R.string.car_browser_bookmarks_empty))
        val saved = WebBookmarkStore.list(carContext)
        page = page.coerceAtMost((saved.size - 1).coerceAtLeast(0) / 4)
        if (page > 0) {
            items.addItem(Row.Builder().setTitle(carContext.getString(R.string.car_browser_bookmarks_previous))
                .setOnClickListener { page--; invalidate() }.build())
        }
        saved.drop(page * 4).take(4).forEach { bookmark ->
            items.addItem(
                Row.Builder()
                    .setTitle(bookmark.title)
                    .addText(
                        BrowserDisplayUrl.compact(bookmark.url).let {
                            if (manageMode) carContext.getString(R.string.car_bookmarks_tap_to_remove, it) else it
                        }
                    )
                    .setOnClickListener {
                        if (manageMode) {
                            WebBookmarkStore.remove(carContext, bookmark.url)
                            CarToast.makeText(
                                carContext,
                                carContext.getString(R.string.car_browser_bookmarks_removed, bookmark.title),
                                CarToast.LENGTH_SHORT
                            ).show()
                            invalidate()
                        } else {
                            setResult(bookmark.url)
                            screenManager.pop()
                        }
                    }
                    .build()
            )
        }
        if ((page + 1) * 4 < saved.size) {
            items.addItem(Row.Builder().setTitle(carContext.getString(R.string.car_browser_bookmarks_more))
                .setOnClickListener { page++; invalidate() }.build())
        }
        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.car_browser_bookmarks_title))
                    .setStartHeaderAction(Action.BACK)
                    .addEndHeaderAction(
                        Action.Builder()
                            .setIcon(CarIcons.of(carContext, if (manageMode) CarIcons.DONE else CarIcons.MANAGE))
                            .setOnClickListener { manageMode = !manageMode; invalidate() }
                            .build()
                    )
                    .build()
            )
            .setSingleList(items.build())
            .build()
    }
}
