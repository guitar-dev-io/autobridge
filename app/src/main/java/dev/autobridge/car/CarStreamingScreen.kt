package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import dev.autobridge.R
import dev.autobridge.library.StreamingFavoritesStore
import dev.autobridge.library.StreamingIcons
import dev.autobridge.library.StreamingLinks

/**
 * The Streaming home tile: an icon grid of the driver's favorites (starred) followed by the shared
 * [StreamingLinks] catalog, ending in a tile to manage the favorites.
 *
 * Each icon loads its site into the same session-scoped car browser the YouTube tile uses, so
 * playback, audio focus, cookies and the parked gate behave exactly as for any other page.
 */
class CarStreamingScreen(carContext: CarContext) : Screen(carContext) {
    private companion object {
        const val ICON_SIZE_PX = 128

        /** Hosts cap a grid; this is the fallback when the host does not say. */
        const val DEFAULT_GRID_LIMIT = 24
    }

    override fun onGetTemplate(): Template {
        val favorites = StreamingFavoritesStore.list(carContext)
        val favoriteUrls = favorites.map { it.url }.toSet()
        val limit = runCatching {
            carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID)
        }.getOrDefault(DEFAULT_GRID_LIMIT).coerceIn(6, 100)

        val grid = ItemList.Builder()
        // The last tile is always "manage", so the others leave it a place.
        var room = limit - 1
        favorites.forEach { favorite ->
            if (room <= 0) return@forEach
            grid.addItem(item(favorite.title, favorite.url, starred = true, note = null))
            room--
        }
        StreamingLinks.all.filterNot { it.url in favoriteUrls }.forEach { link ->
            if (room <= 0) return@forEach
            val note = if (StreamingLinks.mayNotPlay(link)) carContext.getString(R.string.car_streaming_maybe) else null
            grid.addItem(item(link.title, link.url, starred = false, note = note))
            room--
        }
        grid.addItem(
            GridItem.Builder()
                .setTitle(carContext.getString(R.string.car_streaming_manage))
                .setImage(icon(StreamingIcons.MANAGE, starred = false))
                .setOnClickListener {
                    CarNavigation.open(screenManager, "CarStreamingFavoritesScreen") { CarStreamingFavoritesScreen(carContext) }
                }
                .build()
        )

        return GridTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.car_streaming_title))
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .setSingleList(grid.build())
            .build()
    }

    private fun item(title: String, url: String, starred: Boolean, note: String?): GridItem =
        GridItem.Builder()
            .setTitle(title)
            .apply { note?.let { setText(it) } }
            .setImage(icon(StreamingIcons.styleFor(title, url), starred))
            .setOnClickListener { open(url) }
            .build()

    private fun icon(style: StreamingIcons.Style, starred: Boolean): CarIcon =
        CarIcon.Builder(IconCompat.createWithBitmap(StreamingIcons.bitmap(style, ICON_SIZE_PX, starred))).build()

    /** Same entry rule as the home tiles: a site already open is returned to, not reloaded. */
    private fun open(url: String) {
        val renderer = dev.autobridge.browser.CarBrowserRuntime.renderer(carContext)
        if (!dev.autobridge.browser.BrowserSiteEntry.resumes(renderer.livePageUrl, url)) {
            renderer.load(url)
        }
        CarNavigation.open(screenManager, "CarBrowserScreen") { CarBrowserScreen(carContext) }
    }
}
