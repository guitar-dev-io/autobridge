package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.R
import dev.autobridge.entertainment.WebBookmarkStore
import dev.autobridge.iptv.IptvHistoryStore
import dev.autobridge.iptv.IptvKind
import dev.autobridge.media.MediaPlaybackClient

/**
 * The Favorites tile: starred TV/Radio channels plus saved web pages in one place.
 *
 * Both lists already exist ([IptvHistoryStore] and [WebBookmarkStore]); this screen only composes
 * them, so starring a channel on the phone shows up here without any extra sync step.
 */
class CarFavoritesScreen(carContext: CarContext) : Screen(carContext) {
    private val mediaPlayback = MediaPlaybackClient(carContext)

    init {
        mediaPlayback.connect(onConnected = { invalidate() }, onError = { invalidate() })
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = mediaPlayback.disconnect()
        })
    }

    override fun onGetTemplate(): Template {
        val channels = IptvHistoryStore.favorites(carContext)
        val bookmarks = WebBookmarkStore.list(carContext)
        if (channels.isEmpty() && bookmarks.isEmpty()) {
            return MessageTemplate.Builder(carContext.getString(R.string.car_favorites_empty))
                .setHeader(Header.Builder().setTitle(carContext.getString(R.string.car_favorites_title)).setStartHeaderAction(Action.BACK).build())
                .build()
        }

        val template = ListTemplate.Builder()
            .setHeader(Header.Builder().setTitle(carContext.getString(R.string.car_favorites_title)).setStartHeaderAction(Action.BACK).build())

        if (channels.isNotEmpty()) {
            val list = ItemList.Builder()
            CarListPaging.page(carContext, channels, 0).items.forEach { item ->
                list.addItem(
                    Row.Builder()
                        .setTitle(item.title)
                        .addText(carContext.getString(if (item.kind == IptvKind.RADIO) R.string.car_iptv_radio else R.string.car_iptv_tv))
                        .setOnClickListener {
                            CarIptvPlayback.play(
                                this, carContext, mediaPlayback, item.title, item.url, item.kind
                            )
                        }
                        .build()
                )
            }
            template.addSectionedList(SectionedItemList.create(list.build(), carContext.getString(R.string.car_favorites_channels)))
        }

        if (bookmarks.isNotEmpty()) {
            val list = ItemList.Builder()
            CarListPaging.page(carContext, bookmarks, 0).items.forEach { bookmark ->
                list.addItem(
                    Row.Builder()
                        .setTitle(bookmark.title)
                        .addText(bookmark.url)
                        .setBrowsable(true)
                        .setOnClickListener {
                            val browser = CarBrowserScreen(carContext)
                            browser.openUrl(bookmark.url)
                            screenManager.push(browser)
                        }
                        .build()
                )
            }
            template.addSectionedList(SectionedItemList.create(list.build(), carContext.getString(R.string.car_favorites_web)))
        }

        return template.build()
    }
}
