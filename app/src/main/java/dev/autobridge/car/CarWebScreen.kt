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
import dev.autobridge.R
import dev.autobridge.entertainment.WebBookmark
import dev.autobridge.entertainment.WebBookmarkStore

/** Opens websites inside the car browser and keeps bookmarks on the same navigation stack. */
class CarWebScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_web_open_browser))
                    .addText(carContext.getString(R.string.car_web_open_browser_caption))
                    .setBrowsable(true)
                    .setOnClickListener { openUrl("https://www.google.com") }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_web_youtube))
                    .addText(carContext.getString(R.string.car_web_youtube_caption))
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
                    .setTitle(carContext.getString(R.string.car_web_title))
                    .setStartHeaderAction(Action.BACK)
                    .addEndHeaderAction(
                        Action.Builder()
                            .setIcon(CarIcons.of(carContext, CarIcons.MANAGE))
                            .setOnClickListener { CarNavigation.open(screenManager, "CarBookmarksScreen") { CarBookmarksScreen(carContext) } }
                            .build()
                    )
                    .build()
            )
            .setSingleList(list.build())
            .build()
    }

    private fun openUrl(url: String) {
        // The renderer is session-scoped, so loading through it works whether the browser screen is
        // about to be created or is already open further down the stack.
        //
        // A site shortcut for a site already open is returned to rather than reloaded, which is what
        // keeps a playing page alive; a bookmark to a specific page always loads, because it is not
        // a bare site root. [dev.autobridge.browser.BrowserSiteEntry] draws that line.
        val renderer = dev.autobridge.browser.CarBrowserRuntime.renderer(carContext)
        if (!dev.autobridge.browser.BrowserSiteEntry.resumes(renderer.livePageUrl, url)) {
            renderer.load(url)
        }
        CarNavigation.open(screenManager, "CarBrowserScreen") { CarBrowserScreen(carContext) }
    }

    private fun displayHost(url: String): String =
        runCatching { Uri.parse(url).host ?: url }.getOrDefault(url)
}
