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
import dev.autobridge.core.state.RecentActivityStore
import dev.autobridge.iptv.IptvCatalog
import dev.autobridge.iptv.IptvCatalogData
import dev.autobridge.iptv.IptvEntry
import dev.autobridge.iptv.IptvHistoryStore
import dev.autobridge.iptv.IptvKind
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
    private val title = if (kind == IptvKind.RADIO) "Radio" else "TV"

    override fun onGetTemplate(): Template {
        val sources = IptvSourceStore.list(carContext, kind)
        if (sources.isEmpty()) {
            return MessageTemplate.Builder(
                "No $title source yet.\nAdd an Xtream account or an M3U playlist on the phone."
            )
                .setHeader(Header.Builder().setTitle(title).setStartHeaderAction(Action.BACK).build())
                .addAction(
                    Action.Builder().setTitle("Open on phone")
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
                    .setTitle("Recently played")
                    .addText("${recent.size} items")
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
                            IptvCatalog.isLoading(source.id) -> "Loading…"
                            cached != null -> "${cached.entries.size} entries"
                            else -> "Tap to load"
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
        CarToast.makeText(carContext, "Loading ${source.name}…", CarToast.LENGTH_SHORT).show()
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
            CarToast.makeText(carContext, "Continue on the phone", CarToast.LENGTH_LONG).show()
        }.onFailure {
            CarToast.makeText(carContext, "Could not open the phone screen", CarToast.LENGTH_SHORT).show()
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
                    .addText("${category.count} entries")
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
                    .setTitle("Show more")
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
                            "Favourite".takeIf { favorite },
                            "Catch-up".takeIf { entry.supportsCatchup }
                        ).joinToString(" • ").ifBlank { "Tap to play" }
                    )
                    .setBrowsable(entry.isSeriesFolder)
                    .setOnClickListener { open(entry) }
                    .build()
            )
        }
        if (paged.hasMore) {
            list.addItem(
                Row.Builder()
                    .setTitle("Show more")
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
            CarToast.makeText(carContext, "Loading ${entry.title}…", CarToast.LENGTH_SHORT).show()
            IptvCatalog.loadEpisodes(source, entry) { episodes ->
                if (episodes.isEmpty()) {
                    CarToast.makeText(carContext, "No episodes available", CarToast.LENGTH_SHORT).show()
                } else {
                    screenManager.push(
                        CarIptvEntriesScreen(carContext, source, episodes, entry.title)
                    )
                }
            }
            return
        }
        IptvHistoryStore.recordPlayback(carContext, source, entry)
        CarIptvPlayback.play(this, carContext, mediaPlayback, entry.title, entry.url, source.kind)
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
                            this, carContext, mediaPlayback, item.title, item.url, item.kind
                        )
                    }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setHeader(
                Header.Builder().setTitle("Recently played").setStartHeaderAction(Action.BACK).build()
            )
            .setSingleList(list.build())
            .build()
    }
}

/**
 * One playback entry point for every IPTV car screen: radio stays on the MediaSession so audio
 * survives further browsing, while TV takes over the car surface through [CarVideoScreen].
 */
internal object CarIptvPlayback {
    fun play(
        screen: Screen,
        carContext: CarContext,
        mediaPlayback: MediaPlaybackClient,
        title: String,
        url: String,
        kind: IptvKind
    ) {
        if (url.isBlank()) {
            CarToast.makeText(carContext, "This entry has no stream address", CarToast.LENGTH_SHORT).show()
            return
        }
        RecentActivityStore.record(
            carContext,
            RecentActivityStore.Entry(
                RecentActivityStore.Kind.MEDIA, title, if (kind == IptvKind.RADIO) "Radio" else "TV", url
            )
        )
        val screens = screen.screenManager
        if (kind == IptvKind.RADIO) {
            if (!mediaPlayback.isConnected) {
                CarToast.makeText(carContext, "Connecting to the player…", CarToast.LENGTH_SHORT).show()
                return
            }
            mediaPlayback.play(url, title)
            screens.push(CarNowPlayingScreen(carContext))
        } else {
            screens.push(CarVideoScreen(carContext, url, title))
        }
    }
}
