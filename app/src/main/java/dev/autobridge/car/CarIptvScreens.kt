package dev.autobridge.car

import android.content.Intent
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.R
import dev.autobridge.browser.CarBrowserRuntime
import dev.autobridge.core.state.RecentActivityStore
import dev.autobridge.iptv.IptvCatalog
import dev.autobridge.iptv.IptvCatalogData
import dev.autobridge.iptv.IptvEntry
import dev.autobridge.iptv.IptvHistoryStore
import dev.autobridge.iptv.IptvKind
import dev.autobridge.iptv.IptvPlayback
import dev.autobridge.iptv.IptvSource
import dev.autobridge.iptv.IptvSourceStore
import dev.autobridge.library.LibraryActivity
import dev.autobridge.media.MediaPlaybackClient

/**
 * Android Auto browsing for the TV and Radio sections.
 *
 * The car templates cannot host a text field, so accounts are added on the phone; these screens
 * read the same [IptvSourceStore] and the same [IptvCatalog] cache, which means a portal already
 * loaded on the phone opens on the head unit with no second network round trip.
 *
 * Playback follows the existing split: video goes to [CarVideoScreen] (which attaches the car
 * surface to the shared MediaSession) and radio goes to the MediaSession directly, so audio keeps
 * working while the driver browses on.
 */
class CarIptvSourcesScreen(
    carContext: CarContext,
    private val kind: IptvKind
) : Screen(carContext) {
    private val title = carContext.getString(
        if (kind == IptvKind.RADIO) R.string.car_iptv_radio else R.string.car_iptv_tv
    )

    override fun onGetTemplate(): Template {
        val sources = IptvSourceStore.list(carContext, kind)
        if (sources.isEmpty()) {
            return MessageTemplate.Builder(
                carContext.getString(R.string.car_iptv_no_source, title)
            )
                .setHeader(Header.Builder().setTitle(title).setStartHeaderAction(Action.BACK).build())
                .addAction(
                    Action.Builder().setTitle(carContext.getString(R.string.car_iptv_open_on_phone))
                        .setOnClickListener { openOnPhone() }
                        .build()
                )
                .build()
        }

        val list = ItemList.Builder()
        val recent = IptvHistoryStore.recent(carContext, kind)
        if (recent.isNotEmpty()) {
            list.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_iptv_recent))
                    .addText(
                        carContext.resources.getQuantityString(
                            R.plurals.car_iptv_items, recent.size, recent.size
                        )
                    )
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(CarIptvRecentScreen(carContext, kind)) }
                    .build()
            )
        }
        sources.forEach { source ->
            val cached = IptvCatalog.cached(source.id)
            list.addItem(
                Row.Builder()
                    .setTitle(source.name)
                    .addText(
                        when {
                            IptvCatalog.isLoading(source.id) ->
                                carContext.getString(R.string.car_iptv_loading)
                            cached != null -> carContext.resources.getQuantityString(
                                R.plurals.car_iptv_entries,
                                cached.entries.size,
                                cached.entries.size
                            )
                            else -> carContext.getString(R.string.car_iptv_tap_to_load)
                        }
                    )
                    .setBrowsable(true)
                    .setOnClickListener { openSource(source) }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setHeader(Header.Builder().setTitle(title).setStartHeaderAction(Action.BACK).build())
            .setSingleList(list.build())
            .build()
    }

    private fun openSource(source: IptvSource) {
        IptvCatalog.cached(source.id)?.let { data ->
            screenManager.push(CarIptvCategoriesScreen(carContext, source, data))
            return
        }
        CarToast.makeText(
            carContext,
            carContext.getString(R.string.car_iptv_loading_named, source.name),
            CarToast.LENGTH_SHORT
        ).show()
        IptvCatalog.load(carContext, source) { result ->
            when (result) {
                is IptvCatalog.Result.Ready ->
                    screenManager.push(CarIptvCategoriesScreen(carContext, source, result.data))
                is IptvCatalog.Result.Failed ->
                    CarToast.makeText(carContext, result.message, CarToast.LENGTH_LONG).show()
            }
            invalidate()
        }
        invalidate()
    }

    private fun openOnPhone() {
        val section = if (kind == IptvKind.RADIO) LibraryActivity.Section.RADIO else LibraryActivity.Section.TV
        runCatching {
            carContext.startActivity(
                LibraryActivity.intent(carContext, section).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            CarToast.makeText(
                carContext,
                carContext.getString(R.string.car_iptv_continue_on_phone),
                CarToast.LENGTH_LONG
            ).show()
        }.onFailure {
            CarToast.makeText(
                carContext,
                carContext.getString(R.string.car_iptv_phone_screen_failed),
                CarToast.LENGTH_SHORT
            ).show()
        }
    }
}

/** Categories inside one source. Large accounts are paged by [CarListPaging]. */
class CarIptvCategoriesScreen(
    carContext: CarContext,
    private val source: IptvSource,
    private val data: IptvCatalogData,
    private val page: Int = 0
) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val paged = CarListPaging.page(carContext, data.categories, page)
        val list = ItemList.Builder()
        paged.items.forEach { category ->
            list.addItem(
                Row.Builder()
                    .setTitle(category.name)
                    .addText(
                        carContext.resources.getQuantityString(
                            R.plurals.car_iptv_entries, category.count, category.count
                        )
                    )
                    .setBrowsable(true)
                    .setOnClickListener {
                        screenManager.push(
                            CarIptvEntriesScreen(
                                carContext, source, data.entriesIn(category.id), category.name
                            )
                        )
                    }
                    .build()
            )
        }
        if (paged.hasMore) {
            list.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_iptv_show_more))
                    .setBrowsable(true)
                    .setOnClickListener {
                        screenManager.push(CarIptvCategoriesScreen(carContext, source, data, page + 1))
                    }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setHeader(Header.Builder().setTitle(source.name).setStartHeaderAction(Action.BACK).build())
            .setSingleList(list.build())
            .build()
    }
}

/** Channels, movies or episodes in one category, with playback and favourite toggling. */
class CarIptvEntriesScreen(
    carContext: CarContext,
    private val source: IptvSource,
    private val entries: List<IptvEntry>,
    private val title: String,
    private val page: Int = 0
) : Screen(carContext) {
    private val mediaPlayback = MediaPlaybackClient(carContext)

    init {
        mediaPlayback.connect(onConnected = { invalidate() }, onError = { invalidate() })
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = mediaPlayback.disconnect()
        })
    }

    override fun onGetTemplate(): Template {
        val paged = CarListPaging.page(carContext, entries, page)
        val list = ItemList.Builder()
        paged.items.forEach { entry ->
            val favorite = entry.url.isNotBlank() && IptvHistoryStore.isFavorite(carContext, entry.url)
            list.addItem(
                Row.Builder()
                    .setTitle(entry.title)
                    .addText(
                        listOfNotNull(
                            entry.subtitle.takeIf { it.isNotBlank() },
                            carContext.getString(R.string.car_iptv_opens_in_browser)
                                .takeIf { entry.isWebPage },
                            carContext.getString(R.string.car_iptv_favourite).takeIf { favorite },
                            carContext.getString(R.string.car_iptv_catchup)
                                .takeIf { entry.supportsCatchup }
                        ).joinToString(" • ")
                            .ifBlank { carContext.getString(R.string.car_iptv_tap_to_play) }
                    )
                    .setBrowsable(entry.isSeriesFolder)
                    .setOnClickListener { open(entry) }
                    .build()
            )
        }
        if (paged.hasMore) {
            list.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_iptv_show_more))
                    .setBrowsable(true)
                    .setOnClickListener {
                        screenManager.push(
                            CarIptvEntriesScreen(carContext, source, entries, title, page + 1)
                        )
                    }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setHeader(Header.Builder().setTitle(title).setStartHeaderAction(Action.BACK).build())
            .setSingleList(list.build())
            .build()
    }

    private fun open(entry: IptvEntry) {
        if (entry.isSeriesFolder) {
            CarToast.makeText(
                carContext,
                carContext.getString(R.string.car_iptv_loading_named, entry.title),
                CarToast.LENGTH_SHORT
            ).show()
            IptvCatalog.loadEpisodes(source, entry) { episodes ->
                if (episodes.isEmpty()) {
                    CarToast.makeText(
                        carContext,
                        carContext.getString(R.string.car_iptv_no_episodes),
                        CarToast.LENGTH_SHORT
                    ).show()
                } else {
                    screenManager.push(
                        CarIptvEntriesScreen(carContext, source, episodes, entry.title)
                    )
                }
            }
            return
        }
        IptvHistoryStore.recordPlayback(carContext, source, entry)
        CarIptvPlayback.play(
            this, carContext, mediaPlayback, entry.title, entry.url, source.kind, entry.playback
        )
    }
}

/** Replay list backed by [IptvHistoryStore], so a channel restarts without reloading the portal. */
class CarIptvRecentScreen(carContext: CarContext, private val kind: IptvKind) : Screen(carContext) {
    private val mediaPlayback = MediaPlaybackClient(carContext)

    init {
        mediaPlayback.connect(onConnected = { invalidate() }, onError = { invalidate() })
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = mediaPlayback.disconnect()
        })
    }

    override fun onGetTemplate(): Template {
        val items = IptvHistoryStore.recent(carContext, kind)
        val list = ItemList.Builder()
        items.forEach { item ->
            list.addItem(
                Row.Builder()
                    .setTitle(item.title)
                    .addText(item.type.name.lowercase().replaceFirstChar { it.uppercase() })
                    .setOnClickListener {
                        CarIptvPlayback.play(
                            this, carContext, mediaPlayback, item.title, item.url, item.kind,
                            item.playback
                        )
                    }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.car_iptv_recent))
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .setSingleList(list.build())
            .build()
    }
}

/**
 * One playback entry point for every IPTV car screen: radio stays on the MediaSession so audio
 * survives further browsing, while TV takes over the car surface through [CarVideoScreen].
 *
 * A channel whose playlist entry is a YouTube or Twitch page takes a third route — the car browser
 * — because no media player can open a watch page. The browser keeps its own parked-state gate.
 */
internal object CarIptvPlayback {
    fun play(
        screen: Screen,
        carContext: CarContext,
        mediaPlayback: MediaPlaybackClient,
        title: String,
        url: String,
        kind: IptvKind,
        playback: IptvPlayback = IptvPlayback.STREAM
    ) {
        if (url.isBlank()) {
            CarToast.makeText(
                carContext,
                carContext.getString(R.string.car_iptv_no_stream),
                CarToast.LENGTH_SHORT
            ).show()
            return
        }
        if (playback == IptvPlayback.WEB_PAGE) {
            openInCarBrowser(screen, carContext, url, title, kind)
            return
        }
        RecentActivityStore.record(
            carContext,
            RecentActivityStore.Entry(
                RecentActivityStore.Kind.MEDIA,
                title,
                carContext.getString(
                    if (kind == IptvKind.RADIO) R.string.car_iptv_radio else R.string.car_iptv_tv
                ),
                url
            )
        )
        val screens = screen.screenManager
        if (kind == IptvKind.RADIO) {
            if (!mediaPlayback.isConnected) {
                CarToast.makeText(
                    carContext,
                    carContext.getString(R.string.car_iptv_connecting),
                    CarToast.LENGTH_SHORT
                ).show()
                return
            }
            mediaPlayback.play(url, title)
            screens.push(CarNowPlayingScreen(carContext))
        } else {
            CarVideoLauncher.open(screens, carContext, url, title)
        }
    }

    private fun openInCarBrowser(
        screen: Screen,
        carContext: CarContext,
        url: String,
        title: String,
        kind: IptvKind
    ) {
        RecentActivityStore.record(
            carContext,
            RecentActivityStore.Entry(
                RecentActivityStore.Kind.BROWSER, title,
                carContext.getString(
                    if (kind == IptvKind.RADIO) R.string.car_iptv_radio else R.string.car_iptv_tv
                ),
                url
            )
        )
        // The renderer is session-scoped, so loading through it works whether the browser screen is
        // about to be created or is already open further down the stack.
        CarBrowserRuntime.renderer(carContext).load(url)
        CarNavigation.open(screen.screenManager, "CarBrowserScreen") { CarBrowserScreen(carContext) }
    }
}
