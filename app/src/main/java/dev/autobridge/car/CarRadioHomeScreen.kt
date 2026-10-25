package dev.autobridge.car

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.annotation.DrawableRes
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.R
import dev.autobridge.design.CarLook
import dev.autobridge.display.CarSurfaceCanvas
import dev.autobridge.iptv.IptvChannelQueue
import dev.autobridge.iptv.IptvEntry
import dev.autobridge.iptv.IptvHistoryStore
import dev.autobridge.iptv.IptvKind
import dev.autobridge.iptv.IptvPlayback
import dev.autobridge.iptv.IptvSourceStore
import dev.autobridge.iptv.RadioBrowserBrowse
import dev.autobridge.iptv.RadioCategory
import dev.autobridge.iptv.RadioProvinceRepository
import dev.autobridge.media.MediaPlaybackClient
import dev.autobridge.mirror.MirrorSurfaceOwnership
import dev.autobridge.radio.InternetRadioSource
import dev.autobridge.radio.RadioRepository
import dev.autobridge.ui.ImageLoader

/**
 * Radio on the car, after the radio design's car screen: a round back button, the five
 * [RadioCategory]s as pills along the top and a search button, and under them each category's
 * stations as a scrolling grid of logo cards, [CarRadioGridLayout.COLUMNS] across. A tap on a card
 * plays it; the station on the air is the selected card with the accent edge. Colours, cards,
 * corners and text size follow the car's Appearance ([CarLook]), live.
 *
 * It draws on the host surface like [CarRadioNowPlayingScreen] (NavigationTemplate +
 * [CarSurfaceCanvas], [MirrorSurfaceOwnership] for the one surface callback), which is what lets it
 * have the back button, five tabs and the column count the host-drawn TabTemplate did not allow.
 * A host below Car API 5 delivers no surface taps, so [open] gives it the list landing instead.
 *
 * Each network tab fetches through [RadioBrowserBrowse] when first shown and drops a stale answer by
 * key. A category longer than [MAX_STATIONS] ends in an "All stations" card that opens its paged
 * list; In Country also ends in the driver's own lists when there are any.
 */
class CarRadioHomeScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {
    companion object {
        /** Radio's landing for this host: the drawn grid from Car API 5, the list before it. */
        fun open(carContext: CarContext): Screen =
            if (carContext.carAppApiLevel >= 5) CarRadioHomeScreen(carContext)
            else CarIptvSourcesScreen(carContext, IptvKind.RADIO)

        /** Every category is a tab: the surface has room for five pills, the template had four. */
        val TABS = listOf(
            RadioCategory.IN_COUNTRY, RadioCategory.LOCAL, RadioCategory.WORLD,
            RadioCategory.PODCASTS, RadioCategory.FAVORITES,
        )

        /** Cards drawn per category before the "All stations" card takes over. */
        const val MAX_STATIONS = 60

        private const val BASELINE_DPI = 160
        /** A fling travels this share of its velocity (px/s) in one step. */
        private const val FLING_SHARE = 0.35f
    }

    private sealed interface State {
        object Loading : State
        data class Ready(val entries: List<IptvEntry>) : State
        data class Failed(val message: String) : State
    }

    /** What is on screen now: the drawing model and what each card does. */
    private class Model(val content: CarRadioGridRenderer.Content, val actions: List<() -> Unit>, val messageAction: (() -> Unit)?)

    private val appManager = carContext.getCarService(AppManager::class.java)
    private val mediaPlayback = MediaPlaybackClient(carContext)
    private val renderer by lazy { CarRadioGridRenderer(carContext) }
    private val handler = Handler(Looper.getMainLooper())
    /** Settings > Appearance changed on the phone: repaint in the new look. */
    private val lookWatch = CarLookWatch { if (active) render() }
    private var selected = RadioCategory.IN_COUNTRY
    private val states = mutableMapOf<String, State>()

    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfaceDpi = 0
    private var stableArea: Rect? = null
    private var visibleArea: Rect? = null
    private var active = false
    private var pressed: CarRadioGridRegion? = null
    private var scroll = 0f
    private var layout: CarRadioGridLayout? = null
    private var model: Model? = null

    private var sessionPlayer: androidx.media3.common.Player? = null
    private val sessionListener = object : androidx.media3.common.Player.Listener {
        override fun onEvents(player: androidx.media3.common.Player, events: androidx.media3.common.Player.Events) {
            if (active) render()
        }
    }

    private val logos = CarRadioGridRenderer.Logos { url ->
        if (url.isNullOrBlank()) null else ImageLoader.bitmap(carContext, url) { handler.post { if (active) render() } }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                active = true
                pressed = null
                MirrorSurfaceOwnership.claim(this@CarRadioHomeScreen)
                appManager.setSurfaceCallback(this@CarRadioHomeScreen)
                watchSession()
                lookWatch.start()
                load(selected)
                render()
            }

            override fun onStop(owner: LifecycleOwner) {
                active = false
                handler.removeCallbacksAndMessages(null)
                lookWatch.stop()
                unwatchSession()
                releaseSurface()
                if (MirrorSurfaceOwnership.release(this@CarRadioHomeScreen)) appManager.setSurfaceCallback(null)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                mediaPlayback.disconnect()
            }
        })
    }

    override fun onGetTemplate(): Template =
        // NavigationTemplate needs a non-empty strip; an icon-only Home action fills it (never
        // Action.APP_ICON in a strip: see CarBrowserScreen). The drawn back button is the main way out.
        NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setIcon(CarIcons.of(carContext, R.drawable.ic_car_home))
                            .setOnClickListener { screenManager.popToRoot() }
                            .build()
                    )
                    .build()
            )
            .build()

    // --- Data ---------------------------------------------------------------------------------

    private fun province(): String? = RadioProvinceRepository.selected(carContext).takeIf { it.isNotBlank() }

    /** Fetches [category]'s stations unless they are already here or on the way. */
    private fun load(category: RadioCategory) {
        if (category == RadioCategory.FAVORITES) return
        if (category == RadioCategory.LOCAL && province() == null) return
        val key = category.cacheKey(province()) ?: return
        if (states[key] is State.Ready || states[key] == State.Loading) return
        states[key] = State.Loading
        RadioBrowserBrowse.load(category, province()) { result ->
            states[result.key] = when (result) {
                is RadioBrowserBrowse.Result.Ready -> State.Ready(result.entries)
                is RadioBrowserBrowse.Result.Failed -> State.Failed(result.message)
            }
            handler.post { if (active) render() }
        }
    }

    private fun buildModel(): Model {
        val tabs = TABS.map { CarRadioGridRenderer.Tab(carContext.getString(tabLabel(it)), tabIcon(it)) }
        val selectedIndex = TABS.indexOf(selected)
        fun message(text: String, action: String? = null, onAction: (() -> Unit)? = null) = Model(
            CarRadioGridRenderer.Content(tabs, selectedIndex, emptyList(), text, action), emptyList(), onAction
        )

        if (selected == RadioCategory.FAVORITES) {
            val favorites = IptvHistoryStore.favorites(carContext, IptvKind.RADIO).filter { it.url.isNotBlank() }
            if (favorites.isEmpty()) return message(carContext.getString(R.string.radio_browse_empty))
            val logoByUrl = favorites.associate { it.url to it.logo }
            val cells = favorites.map { stationCell(it.title, it.logo, it.url) }
            val actions = favorites.map { item ->
                {
                    RadioRepository.recordPlayback(carContext, RadioRepository.RadioHistory.toRadioStation(item))
                    CarIptvPlayback.play(
                        this, carContext, mediaPlayback, item.title, item.url, IptvKind.RADIO, item.playback,
                        IptvChannelQueue.aroundItems(favorites, item, CarIptvCheck::isDead),
                        logoFor = { logoByUrl[it] }
                    )
                }
            }
            return Model(CarRadioGridRenderer.Content(tabs, selectedIndex, cells), actions, null)
        }
        if (selected == RadioCategory.LOCAL && province() == null) {
            return message(
                carContext.getString(R.string.radio_local_pick_title),
                carContext.getString(R.string.radio_local_pick_cta)
            ) {
                screenManager.push(CarRadioProvinceScreen(carContext) {
                    load(RadioCategory.LOCAL)
                    render()
                })
            }
        }
        val key = selected.cacheKey(province())
        return when (val state = key?.let { states[it] } ?: State.Loading) {
            State.Loading -> message(carContext.getString(R.string.radio_browse_loading))
            is State.Failed -> message(state.message)
            is State.Ready -> {
                // The directory lists some stations twice (one per stream); the grid shows each once.
                val stations = state.entries.filterNot { CarIptvCheck.isDead(it.url) || it.url.isBlank() }
                    .distinctBy { it.title.trim().lowercase() }
                val shown = stations.take(MAX_STATIONS)
                val logoByUrl = stations.associate { it.url to it.logo }
                val cells = shown.map { stationCell(it.title, it.logo, it.url) }.toMutableList()
                val actions = shown.map<IptvEntry, () -> Unit> { entry -> { play(entry, stations, logoByUrl) } }.toMutableList()
                val category = selected
                if (stations.size > shown.size) {
                    cells += CarRadioGridRenderer.Cell(carContext.getString(R.string.radio_all_stations), icon = R.drawable.ic_tile_radio)
                    actions += { screenManager.push(CarRadioCategoryScreen(carContext, category)) }
                }
                if (category == RadioCategory.IN_COUNTRY && IptvSourceStore.list(carContext, IptvKind.RADIO).isNotEmpty()) {
                    // The driver's own radio lists (M3U, Xtream) still live on the list landing.
                    cells += CarRadioGridRenderer.Cell(carContext.getString(R.string.radio_browse_my_stations), icon = R.drawable.ic_radio_list)
                    actions += { screenManager.push(CarIptvSourcesScreen(carContext, IptvKind.RADIO)) }
                }
                if (cells.isEmpty()) message(carContext.getString(R.string.radio_browse_empty))
                else Model(CarRadioGridRenderer.Content(tabs, selectedIndex, cells), actions, null)
            }
        }
    }

    /**
     * A station card. The one on the air is matched by its stream address, or by its name when the
     * session reports the address differently (a redirect, a resolved playlist).
     */
    private fun stationCell(title: String, logo: String?, url: String): CarRadioGridRenderer.Cell {
        val current = mediaPlayback.currentUri
        val currentName = mediaPlayback.currentItemTitle
        val onAir = (current != null && current == url) ||
            (currentName != null && currentName.trim().equals(title.trim(), ignoreCase = true))
        val status = if (!onAir) null else carContext.getString(
            if (mediaPlayback.isPlaying) R.string.car_lib_playing else R.string.car_lib_paused
        )
        return CarRadioGridRenderer.Cell(title, logo, onAir = onAir, status = status)
    }

    private fun play(entry: IptvEntry, stations: List<IptvEntry>, logoByUrl: Map<String, String>) {
        RadioRepository.recordPlayback(carContext, InternetRadioSource.toStation(entry))
        CarIptvPlayback.play(
            this, carContext, mediaPlayback, entry.title, entry.url, IptvKind.RADIO,
            IptvPlayback.STREAM, IptvChannelQueue.around(stations, entry, CarIptvCheck::isDead),
            logoFor = { logoByUrl[it] }
        )
    }

    private fun select(category: RadioCategory) {
        if (category == selected) return
        selected = category
        scroll = 0f
        load(category)
    }

    // --- Session ------------------------------------------------------------------------------

    private fun watchSession() {
        if (mediaPlayback.isConnected) {
            attachSessionListener()
        } else {
            mediaPlayback.connect(onConnected = {
                if (active) {
                    attachSessionListener()
                    render()
                }
            }, onError = { if (active) render() })
        }
    }

    private fun attachSessionListener() {
        val player = mediaPlayback.player ?: return
        if (sessionPlayer === player) return
        sessionPlayer?.removeListener(sessionListener)
        player.addListener(sessionListener)
        sessionPlayer = player
    }

    private fun unwatchSession() {
        sessionPlayer?.removeListener(sessionListener)
        sessionPlayer = null
    }

    // --- Surface callbacks --------------------------------------------------------------------

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this)) return
        val next = surfaceContainer.surface ?: return
        if (next !== surface) releaseSurface()
        surface = next
        surfaceWidth = surfaceContainer.width
        surfaceHeight = surfaceContainer.height
        surfaceDpi = surfaceContainer.dpi
        CarDisplayInfo.record(surfaceWidth, surfaceHeight, surfaceDpi, source = "radio-grid")
        render()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        if (!MirrorSurfaceOwnership.isOwner(this)) return
        releaseSurface()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this)) return
        this.stableArea = Rect(stableArea)
        render()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this)) return
        this.visibleArea = Rect(visibleArea)
        render()
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this)) return
        scrollBy(distanceY)
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this)) return
        scrollBy(-velocityY * FLING_SHARE)
    }

    private fun scrollBy(dy: Float) {
        val max = layout?.maxScroll ?: return
        val next = (scroll + dy).coerceIn(0f, max)
        if (next == scroll) return
        scroll = next
        render()
    }

    override fun onClick(x: Float, y: Float) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this) || pressed != null) return
        val current = model ?: return
        val hit = layout?.hit(x, y, scroll, current.messageAction != null) ?: return
        pressed = hit
        render()
        handler.postDelayed({
            pressed = null
            if (!active) return@postDelayed
            actOn(hit, current)
            if (active) render()
        }, HomeDashboardTheme.PRESS_FEEDBACK_MS)
    }

    private fun actOn(region: CarRadioGridRegion, current: Model) {
        when (region) {
            CarRadioGridRegion.Back -> screenManager.pop()
            CarRadioGridRegion.Search -> screenManager.push(CarRadioSearchScreen(carContext))
            is CarRadioGridRegion.Tab -> TABS.getOrNull(region.index)?.let(::select)
            is CarRadioGridRegion.Cell -> current.actions.getOrNull(region.index)?.invoke()
            CarRadioGridRegion.MessageAction -> current.messageAction?.invoke()
        }
    }

    // --- Drawing ------------------------------------------------------------------------------

    private fun render() {
        val target = surface ?: return
        if (!active || !MirrorSurfaceOwnership.isOwner(this) || !target.isValid) return
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        val density = (if (surfaceDpi > 0) surfaceDpi else BASELINE_DPI) / BASELINE_DPI.toFloat()
        // The layout is computed every frame, so a new look (text size, font) lays out as it lands.
        renderer.look = CarLook.current()
        val built = buildModel().also { model = it }
        val computed = CarRadioGridLayout.compute(
            safeArea(), density, renderer.measureTabLabels(built.content.tabs, density), built.content.cells.size
        ).also { layout = it }
        scroll = scroll.coerceIn(0f, computed.maxScroll)
        CarSurfaceCanvas.draw(carContext, target) { canvas ->
            renderer.draw(canvas, computed, built.content, scroll, pressed, logos)
        }
    }

    private fun safeArea(): CarRadioBox {
        val full = Rect(0, 0, surfaceWidth, surfaceHeight)
        val inset = listOfNotNull(stableArea, visibleArea)
            .firstOrNull {
                !it.isEmpty && full.contains(it) && it.width() > surfaceWidth / 2 && it.height() > surfaceHeight / 2
            } ?: full
        return CarRadioBox(inset.left.toFloat(), inset.top.toFloat(), inset.right.toFloat(), inset.bottom.toFloat())
    }

    private fun releaseSurface() {
        surface?.let { runCatching { it.release() } }
        surface = null
    }

    /** The design's short tab names; Favourites is "โปรด" on a pill. */
    private fun tabLabel(category: RadioCategory): Int =
        if (category == RadioCategory.FAVORITES) R.string.radio_tab_favorites else category.labelRes

    @DrawableRes
    private fun tabIcon(category: RadioCategory): Int = when (category) {
        RadioCategory.IN_COUNTRY -> R.drawable.ic_car_home
        RadioCategory.LOCAL -> R.drawable.ic_radio_local
        RadioCategory.WORLD -> R.drawable.ic_tile_language
        RadioCategory.PODCASTS -> R.drawable.ic_car_mic
        RadioCategory.FAVORITES -> R.drawable.ic_browser_star
    }
}
