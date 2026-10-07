package dev.autobridge.car

import android.content.Intent
import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.ForegroundCarColorSpan
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.R
import dev.autobridge.browser.CarBrowserRuntime
import dev.autobridge.core.state.RecentActivityStore
import dev.autobridge.iptv.IptvCatalog
import dev.autobridge.iptv.ChannelQueue
import dev.autobridge.iptv.IptvCatalogData
import dev.autobridge.iptv.IptvChannelQueue
import dev.autobridge.iptv.IptvEntry
import dev.autobridge.iptv.IptvEpg
import dev.autobridge.iptv.IptvHistoryStore
import dev.autobridge.iptv.IptvKind
import dev.autobridge.iptv.IptvPlayback
import dev.autobridge.iptv.IptvSource
import dev.autobridge.iptv.IptvSourceStore
import dev.autobridge.iptv.StreamPing
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
 *
 * Channel rows carry the playlist's own logo through [CarChannelLogos], and a page can be checked
 * with [StreamPing] before anything is opened, so a dead or geo-blocked channel is visible as a
 * row that says so rather than as a player that spins.
 */
class CarIptvSourcesScreen(
    carContext: CarContext,
    private val kind: IptvKind
) : Screen(carContext) {
    private val title = carContext.getString(
        if (kind == IptvKind.RADIO) R.string.car_iptv_radio else R.string.car_iptv_tv
    )
    private val mediaPlayback = MediaPlaybackClient(carContext)
    private val logos = CarChannelLogos(carContext) { repaint() }

    init {
        mediaPlayback.connect(onConnected = { invalidate() }, onError = { invalidate() })
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            // The channels the driver actually picks from first — favourites and recent — are
            // checked as soon as TV / Radio opens, quietly, so a dead one has already dropped off
            // the quick rows by the time a finger reaches them.
            override fun onStart(owner: LifecycleOwner) {
                val urls = (IptvHistoryStore.favorites(carContext, kind) + IptvHistoryStore.recent(carContext, kind))
                    .filter { it.playback == IptvPlayback.STREAM }
                    .map { it.url }
                CarIptvCheck.start(carContext, urls, quiet = true) { repaint() }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                logos.stop()
                mediaPlayback.disconnect()
            }
        })
    }

    private fun repaint() {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) invalidate()
    }

    /**
     * Favourite and recent channels as rows that play straight away, at the top of the list.
     * Few enough to stay inside the host's list limit while driving, so a channel the driver
     * watches every day is one tap away instead of a scroll the host locks.
     */
    private fun addQuickRows(list: ItemList.Builder) {
        val favorites = IptvHistoryStore.favorites(carContext, kind)
        val quick = IptvChannelQueue.quick(favorites, IptvHistoryStore.recent(carContext, kind), CarIptvCheck::isDead)
        quick.forEach { item ->
            val favorite = favorites.any { it.url == item.url }
            val row = Row.Builder()
                .setTitle(item.title)
                .addText(
                    carContext.getString(
                        if (favorite) R.string.car_iptv_favourite else R.string.car_iptv_recent
                    )
                )
                .setOnClickListener {
                    CarIptvPlayback.play(
                        this, carContext, mediaPlayback, item.title, item.url, item.kind,
                        item.playback, IptvChannelQueue.aroundItems(quick, item, CarIptvCheck::isDead)
                    )
                }
            logos.icon(item.logo)?.let { row.setImage(it, Row.IMAGE_TYPE_SMALL) }
            list.addItem(row.build())
        }
    }

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

        logos.beginTemplate()
        val list = ItemList.Builder()
        addQuickRows(list)
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
    private val logos = CarChannelLogos(carContext) { repaint() }
    private var autoChecked = false

    init {
        mediaPlayback.connect(onConnected = { invalidate() }, onError = { invalidate() })
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            // The driver should not have to ask whether a channel still works, so the page checks
            // itself once when it opens. Paging to the next screenful is a new screen, and checks
            // itself in turn; coming back to this one does not, because the answers are cached.
            override fun onStart(owner: LifecycleOwner) {
                if (autoChecked) return
                autoChecked = true
                CarIptvCheck.start(
                    carContext,
                    CarIptvCheck.urls(CarListPaging.page(carContext, entries, page).items),
                    quiet = true
                ) { repaint() }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                logos.stop()
                mediaPlayback.disconnect()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val paged = CarListPaging.page(carContext, entries, page)
        // The logo budget is per template, so it starts over for the rows about to be built.
        logos.beginTemplate()
        val list = ItemList.Builder()
        // Pages are cut from the full category, and dead channels are dropped only from the page
        // in view, so a check landing mid-scroll never moves a channel onto another page.
        val shown = paged.items.filterNot { CarIptvCheck.isDead(it.url) }
        // What is on now, for the live channels in view; each answer repaints as it lands.
        IptvEpg.load(source, shown) { repaint() }
        shown.forEach { entry ->
            val favorite = entry.url.isNotBlank() && IptvHistoryStore.isFavorite(carContext, entry.url)
            val row = Row.Builder()
                .setTitle(entry.title)
                .addText(
                    listOfNotNull(
                        IptvEpg.cached(source, entry)?.let { "▶ $it" },
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
            logos.icon(entry.logo)?.let { row.setImage(it, Row.IMAGE_TYPE_SMALL) }
            // A full-list row may carry two lines of text; a check result earns the second one.
            StreamPing.cached(entry.url)?.let { row.addText(CarIptvCheck.text(carContext, it)) }
            list.addItem(row.build())
        }
        CarIptvCheck.hiddenRow(carContext, paged.items.size - shown.size)?.let { list.addItem(it) }
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
            .setHeader(
                Header.Builder()
                    .setTitle(title)
                    .setStartHeaderAction(Action.BACK)
                    .apply {
                        // Only the page in front of the driver is checked - a category can hold
                        // thousands of channels, and the answers would be stale before they land.
                        CarIptvCheck.action(carContext, CarIptvCheck.urls(paged.items)) { repaint() }
                            ?.let { addEndHeaderAction(it) }
                    }
                    .build()
            )
            .setSingleList(list.build())
            .build()
    }

    /** A logo or a check result landed: push a template only while this screen is really up. */
    private fun repaint() {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) invalidate()
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
            this, carContext, mediaPlayback, entry.title, entry.url, source.kind, entry.playback,
            IptvChannelQueue.around(entries, entry, CarIptvCheck::isDead)
        )
    }
}

/** Replay list backed by [IptvHistoryStore], so a channel restarts without reloading the portal. */
class CarIptvRecentScreen(carContext: CarContext, private val kind: IptvKind) : Screen(carContext) {
    private val mediaPlayback = MediaPlaybackClient(carContext)
    private val logos = CarChannelLogos(carContext) { repaint() }
    private var autoChecked = false

    init {
        mediaPlayback.connect(onConnected = { invalidate() }, onError = { invalidate() })
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (autoChecked) return
                autoChecked = true
                val items = IptvHistoryStore.recent(carContext, kind)
                    .filter { it.playback == IptvPlayback.STREAM }
                CarIptvCheck.start(carContext, items.map { it.url }, quiet = true) { repaint() }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                logos.stop()
                mediaPlayback.disconnect()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val all = IptvHistoryStore.recent(carContext, kind)
        val items = all.filterNot { CarIptvCheck.isDead(it.url) }
        logos.beginTemplate()
        val list = ItemList.Builder()
        items.forEach { item ->
            val row = Row.Builder()
                .setTitle(item.title)
                .addText(item.type.name.lowercase().replaceFirstChar { it.uppercase() })
                .setOnClickListener {
                    CarIptvPlayback.play(
                        this, carContext, mediaPlayback, item.title, item.url, item.kind,
                        item.playback, IptvChannelQueue.aroundItems(items, item, CarIptvCheck::isDead)
                    )
                }
            logos.icon(item.logo)?.let { row.setImage(it, Row.IMAGE_TYPE_SMALL) }
            StreamPing.cached(item.url)?.let { row.addText(CarIptvCheck.text(carContext, it)) }
            list.addItem(row.build())
        }
        CarIptvCheck.hiddenRow(carContext, all.size - items.size)?.let { list.addItem(it) }
        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.car_iptv_recent))
                    .setStartHeaderAction(Action.BACK)
                    .apply {
                        // The replay list is where "does this still work?" matters most: these are
                        // the channels the driver already chose once.
                        val urls = all.filter { it.playback == IptvPlayback.STREAM }.map { it.url }
                        CarIptvCheck.action(carContext, urls) { repaint() }
                            ?.let { addEndHeaderAction(it) }
                    }
                    .build()
            )
            .setSingleList(list.build())
            .build()
    }

    private fun repaint() {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) invalidate()
    }
}

/**
 * The "check this page" header action, and the reading of one result, shared by every car screen
 * that lists channels.
 *
 * A head unit cannot show a progress bar inside a list, so the run reports itself the way the car
 * reports everything else: a toast when it starts, a toast with the tally when it finishes, and
 * the rows filling in with [StreamPing]'s cached answers in between.
 */
internal object CarIptvCheck {
    /** The addresses on a page worth probing: a folder and a watch page are not streams. */
    fun urls(entries: List<IptvEntry>): List<String> = entries
        .filter { !it.isSeriesFolder && !it.isWebPage && it.url.isNotBlank() }
        .map { it.url }

    /**
     * The icon-only end header action that re-checks [urls], or null when there is nothing to
     * check. Icon-only is not a style choice: a header action may carry no custom title (see
     * [CarIcons]).
     *
     * The page has already checked itself by the time this is tappable, so the action forgets the
     * remembered answers first - otherwise, inside the freshness window, it would hand back
     * exactly what is already on the rows and look like a button that does nothing.
     */
    fun action(carContext: CarContext, urls: List<String>, repaint: () -> Unit): Action? {
        val checkable = urls.filter { it.isNotBlank() }.distinct()
        if (checkable.isEmpty()) return null
        return Action.Builder()
            .setIcon(CarIcons.of(carContext, CarIcons.SIGNAL))
            .setOnClickListener {
                StreamPing.forget(checkable)
                start(carContext, checkable, quiet = false, repaint = repaint)
            }
            .build()
    }

    /**
     * The row-sized reading of [result], in the language the car is running in and in the colour
     * the result deserves: green answered, amber slow or unknown, red refused or silent.
     *
     * A host honours `ForegroundCarColorSpan` on row text (and ignores every other span), which is
     * the only way an app colours anything inside a template it does not draw itself.
     */
    /** The check found [url] unreachable: the "not responding" rows, which are left off the lists. */
    fun isDead(url: String): Boolean = StreamPing.cached(url) is StreamPing.Result.Unreachable

    /** A plain row saying how many dead channels this list is not showing, or null for none. */
    fun hiddenRow(carContext: CarContext, count: Int): Row? =
        if (count <= 0) null else Row.Builder()
            .setTitle(carContext.getString(R.string.car_iptv_hidden_dead, count))
            .build()

    fun text(carContext: CarContext, result: StreamPing.Result): CharSequence {
        val label = when (result) {
            is StreamPing.Result.Alive ->
                carContext.getString(R.string.car_iptv_ping_ms, result.millis)
            is StreamPing.Result.Refused ->
                carContext.getString(R.string.car_iptv_ping_http, result.status)
            is StreamPing.Result.Unreachable ->
                carContext.getString(R.string.car_iptv_ping_dead)
            StreamPing.Result.Unsupported ->
                carContext.getString(R.string.car_iptv_ping_unsupported)
        }
        val color = when (StreamPing.tone(result)) {
            StreamPing.Tone.GOOD -> CarColor.GREEN
            StreamPing.Tone.SLOW -> CarColor.YELLOW
            StreamPing.Tone.BAD -> CarColor.RED
        }
        return SpannableString(label).apply {
            setSpan(
                ForegroundCarColorSpan.create(color), 0, label.length,
                Spanned.SPAN_INCLUSIVE_EXCLUSIVE
            )
        }
    }

    /**
     * Runs the check. [quiet] is the automatic pass a list makes when it opens: it is work the
     * driver did not ask for, so it says nothing and shows itself only as colour on the rows.
     *
     * The automatic pass stops at [AUTO_LIMIT] addresses. A host can allow a hundred rows in one
     * list, and probing all of them on the way past a category is traffic nobody asked for; the
     * header action still covers the whole page on request.
     */
    fun start(
        carContext: CarContext,
        urls: List<String>,
        quiet: Boolean,
        repaint: () -> Unit
    ) {
        val checkable = urls.filter { it.isNotBlank() }.distinct()
            .let { if (quiet) it.take(AUTO_LIMIT) else it }
        if (checkable.isEmpty()) return
        StreamPing.checkAll(checkable) { progress ->
            repaint()
            if (progress.done && !quiet) {
                CarToast.makeText(
                    carContext,
                    carContext.getString(
                        R.string.car_iptv_ping_done, progress.alive, progress.total
                    ),
                    CarToast.LENGTH_LONG
                ).show()
            }
        }
        if (quiet) return
        CarToast.makeText(
            carContext,
            carContext.getString(R.string.car_iptv_ping_started, checkable.size),
            CarToast.LENGTH_SHORT
        ).show()
    }

    /** What the automatic pass checks: about two screenfuls on a roomy head unit. */
    private const val AUTO_LIMIT = 24
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
        playback: IptvPlayback = IptvPlayback.STREAM,
        queue: ChannelQueue? = null,
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
            if (queue == null) {
                mediaPlayback.play(url, title)
            } else {
                mediaPlayback.playPlaylist(
                    queue.channels.map { it.url }, queue.startIndex, queue.channels.map { it.title }
                )
            }
            screens.push(CarNowPlayingScreen(carContext))
        } else {
            CarVideoLauncher.open(screens, carContext, url, title, queue)
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
