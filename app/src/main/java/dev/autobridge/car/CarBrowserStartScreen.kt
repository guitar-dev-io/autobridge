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
import dev.autobridge.browser.BrowserInputResolver
import dev.autobridge.browser.BrowserSiteEntry
import dev.autobridge.browser.CarBrowserRuntime
import dev.autobridge.browser.SearchEngineStore
import dev.autobridge.entertainment.WebBookmarkStore
import dev.autobridge.entertainment.WebHistoryStore

/**
 * The car browser's start page, the step between the Web card and a page, like AABrowser's: search,
 * the page left open (when there is one), the quick links, and a "support the developer" card, so
 * every visit to the browser passes the one place that asks for support, without getting in the
 * way of a driver who just wants a site.
 *
 * "Open browser" keeps the Web card's old behaviour: the chosen desktop engine where it applies,
 * the built-in browser otherwise. Site tiles on the home (YouTube and the rest) still go straight
 * to their page.
 */
class CarBrowserStartScreen(carContext: CarContext) : Screen(carContext) {
    private companion object {
        const val FALLBACK_GRID_LIMIT = 6
    }

    override fun onGetTemplate(): Template {
        val items = ItemList.Builder()
        items.addItem(cell(carContext.getString(R.string.car_browser_search_go), CarIcons.of(carContext, CarIcons.SEARCH)) { search() })
        val renderer = CarBrowserRuntime.renderer(carContext)
        val open = renderer.livePageUrl?.takeIf { it.startsWith("http") }
        val fixed = mutableListOf<GridItem>()
        fixed += if (open != null) {
            cell(
                renderer.title?.takeIf { it.isNotBlank() } ?: host(open),
                CarIcons.of(carContext, R.drawable.ic_tile_web),
                carContext.getString(R.string.car_browser_start_continue)
            ) { openBuiltIn() }
        } else {
            cell(carContext.getString(R.string.car_web_open_browser), CarIcons.of(carContext, R.drawable.ic_tile_web)) { openBrowser() }
        }
        fixed += cell(
            carContext.getString(R.string.support_card_title),
            CarIcons.of(carContext, R.drawable.ic_browser_support),
            carContext.getString(R.string.car_browser_start_support_caption)
        ) { screenManager.push(CarSupportScreen(carContext)) }
        fixed.forEach(items::addItem)

        // Recent pages first (newest, de-duped against the live page), then bookmarks fill the rest,
        // so the flat grid never exceeds the host's content limit. Empty history adds no recent tile.
        val layout = CarBrowserStartLayout.compute(
            limit = gridLimit(),
            fixedCount = fixed.size,
            liveUrl = open,
            history = WebHistoryStore.list(carContext),
            bookmarks = WebBookmarkStore.list(carContext)
        )
        layout.recent.forEach { entry ->
            val title = entry.title.ifBlank { host(entry.url) }
            val tile = CarIcon.Builder(IconCompat.createWithBitmap(CarStationTile.placeholder(title))).build()
            items.addItem(cell(title, tile, host(entry.url)) { load(entry.url) })
        }
        layout.bookmarks.forEach { bookmark ->
            val tile = CarIcon.Builder(IconCompat.createWithBitmap(CarStationTile.placeholder(bookmark.title))).build()
            items.addItem(cell(bookmark.title, tile, host(bookmark.url)) { load(bookmark.url) })
        }

        return GridTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.car_browser_entry_label))
                    .setStartHeaderAction(Action.BACK)
                    .addEndHeaderAction(
                        Action.Builder()
                            .setIcon(CarIcons.of(carContext, CarIcons.MANAGE))
                            .setOnClickListener { CarNavigation.open(screenManager, "CarBookmarksScreen") { CarBookmarksScreen(carContext) } }
                            .build()
                    )
                    .build()
            )
            .setSingleList(items.build())
            .build()
    }

    private fun cell(title: String, icon: CarIcon, text: String? = null, onClick: () -> Unit): GridItem =
        GridItem.Builder()
            .setTitle(title)
            .apply { if (!text.isNullOrBlank()) setText(text) }
            .setImage(icon, GridItem.IMAGE_TYPE_LARGE)
            .setOnClickListener(onClick)
            .build()

    /** The car's keyboard, then the address or a search on the browser's engine. */
    private fun search() {
        screenManager.pushForResult(CarBrowserSearchScreen(carContext, "")) { result ->
            val text = (result as? String)?.trim().orEmpty()
            if (text.isEmpty()) return@pushForResult
            load(BrowserInputResolver.resolveTyped(text, SearchEngineStore.engine(carContext)))
        }
    }

    /** A site already open is returned to rather than reloaded, as the home's site tiles do. */
    private fun load(url: String) {
        val renderer = CarBrowserRuntime.renderer(carContext)
        if (!BrowserSiteEntry.resumes(renderer.livePageUrl, url)) renderer.load(url)
        openBuiltIn()
    }

    private fun openBuiltIn() {
        CarNavigation.open(screenManager, "CarBrowserScreen") { CarBrowserScreen(carContext) }
    }

    /** The Web card's old target: the chosen desktop engine, else the built-in browser. */
    private fun openBrowser() {
        if (!CarExtensions.openWeb(carContext, screenManager)) openBuiltIn()
    }

    private fun host(url: String): String =
        runCatching { android.net.Uri.parse(url).host?.removePrefix("www.") }.getOrNull() ?: url

    private fun gridLimit(): Int = runCatching {
        carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID)
    }.getOrDefault(FALLBACK_GRID_LIMIT).coerceIn(FALLBACK_GRID_LIMIT, 24)
}
