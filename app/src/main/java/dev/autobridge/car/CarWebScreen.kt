package dev.autobridge.car

import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import dev.autobridge.entertainment.WebBookmark
import dev.autobridge.entertainment.WebBookmarkStore

/** Opens websites inside the car browser and keeps bookmarks on the same navigation stack. */
class CarWebScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle("Open browser")
                    .addText("Enter a website or search the web")
                    .setBrowsable(true)
                    .setOnClickListener { openUrl("https://www.google.com") }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("YouTube")
                    .addText("Open m.youtube.com on the car screen")
                    .setOnClickListener { openUrl("https://m.youtube.com") }
                    .build()
            )

        val bookmarks: List<WebBookmark> = WebBookmarkStore.list(carContext)
        bookmarks.take(4).forEach { bookmark ->
            list.addItem(
                Row.Builder()
                    .setTitle(bookmark.title)
                    .addText(displayHost(bookmark.url))
                    .setOnClickListener { openUrl(bookmark.url) }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle("Browser & Favorites")
                    .setStartHeaderAction(Action.BACK)
                    .addEndHeaderAction(
                        Action.Builder()
                            .setTitle("Manage")
                            .setBackgroundColor(CarColor.BLUE)
                            .setOnClickListener { screenManager.push(CarBookmarksScreen(carContext)) }
                            .build()
                    )
                    .build()
            )
            .setSingleList(list.build())
            .build()
    }

    private fun openUrl(url: String) {
        screenManager.push(CarBrowserScreen(carContext).apply { openUrl(url) })
    }

    private fun displayHost(url: String): String =
        runCatching { Uri.parse(url).host ?: url }.getOrDefault(url)
}
