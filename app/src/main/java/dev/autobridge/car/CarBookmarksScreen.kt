package dev.autobridge.car

import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.CarToast
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

/**
 * Manage the user-editable web bookmarks shown by [CarWebScreen]. Car App templates cannot host a
 * text field, so new entries are added from a curated suggestion set; existing entries can be
 * removed. All changes persist through [WebBookmarkStore].
 */
class CarBookmarksScreen(carContext: CarContext) : Screen(carContext) {
    private val suggestions = listOf(
        WebBookmark("YouTube", "https://m.youtube.com"),
        WebBookmark("YouTube Music", "https://music.youtube.com"),
        WebBookmark("Google", "https://www.google.com"),
        WebBookmark("Google Maps", "https://maps.google.com"),
        WebBookmark("Google News", "https://news.google.com"),
        WebBookmark("Wikipedia", "https://www.wikipedia.org"),
        WebBookmark("Spotify", "https://open.spotify.com"),
        WebBookmark("SoundCloud", "https://soundcloud.com")
    )

    override fun onGetTemplate(): Template {
        val saved = WebBookmarkStore.list(carContext)
        val savedUrls = saved.map { it.url }.toSet()
        val list = ItemList.Builder()

        saved.forEach { bookmark ->
            list.addItem(
                Row.Builder()
                    .setTitle(bookmark.title)
                    .addText("Tap to remove • ${host(bookmark.url)}")
                    .setOnClickListener {
                        WebBookmarkStore.remove(carContext, bookmark.url)
                        CarToast.makeText(carContext, "Removed ${bookmark.title}", CarToast.LENGTH_SHORT).show()
                        invalidate()
                    }
                    .build()
            )
        }

        suggestions.filterNot { it.url in savedUrls }.take(6 - saved.size.coerceAtMost(6)).forEach { suggestion ->
            list.addItem(
                Row.Builder()
                    .setTitle("Add ${suggestion.title}")
                    .addText(host(suggestion.url))
                    .setOnClickListener {
                        if (WebBookmarkStore.add(carContext, suggestion.title, suggestion.url)) {
                            CarToast.makeText(carContext, "Added ${suggestion.title}", CarToast.LENGTH_SHORT).show()
                            invalidate()
                        } else {
                            CarToast.makeText(carContext, "Invalid address", CarToast.LENGTH_SHORT).show()
                        }
                    }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle("Manage bookmarks")
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .setSingleList(list.build())
            .build()
    }

    private fun host(url: String): String =
        runCatching { Uri.parse(url).host ?: url }.getOrDefault(url)
}
