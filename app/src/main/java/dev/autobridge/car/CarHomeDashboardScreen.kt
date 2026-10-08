package dev.autobridge.car

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.R
import dev.autobridge.bridge.AutoBridgeSessionManager
import dev.autobridge.bridge.BridgePlaybackState
import dev.autobridge.bridge.BridgeSource
import dev.autobridge.bridge.EngineKind
import dev.autobridge.core.state.VehicleStateSession
import dev.autobridge.logging.StructuredLog
import dev.autobridge.library.HomeSection
import dev.autobridge.mirror.MirrorSurfaceOwnership
import dev.autobridge.mirror.ProjectionService
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * AutoBridge Home Dashboard.
 *
 * The home is drawn by the app on the car surface (NavigationTemplate) under a compact AutoBridge
 * header: the Now Playing card with its previous / play-pause / next buttons beside the 3 × 2 grid
 * of [HomeMenuCard]s (TV, Radio, Web browser, YouTube, YouTube Music, Streaming), then the items
 * recently sent from the phone with a link to the queue - so it reads as one dark design system
 * instead of host-styled tiles. Layout is computed from the surface size,
 * density and the host's stable area ([HomeDashboardLayout]); Android Auto's own rail, bottom bar,
 * clock and status icons sit outside the surface and are never drawn over or imitated.
 *
 * Every section shows real state or is absent: [HomeDashboardContent] reads the bridge's own
 * session snapshot, the recents the phone sent, and the play queue, and a section with nothing in
 * it gives its space back to the cards.
 *
 * Everything else, and a rotary-friendly route to the six cards, is behind the single action-strip
 * button ([CarHomeMoreScreen]). Hosts older than Car API 5 cannot deliver surface taps, so they keep
 * the previous host-drawn grid.
 *
 * This screen only navigates; each feature owns its own state, so returning here never destroys a
 * browser page, media playback, or mirror session.
 */
class CarHomeDashboardScreen(
    carContext: CarContext,
    private val page: Int = 0
) : Screen(carContext), SurfaceCallback {
    private val speedSubscription = dev.autobridge.speed.SpeedManager.acquire(carContext)
    private val vehicleStateSession = VehicleStateSession.acquire(carContext) {
        ProjectionService.stop(carContext)
        carContext.finishCarApp()
    }

    // --- Surface-drawn menu state ---
    private val appManager = carContext.getCarService(AppManager::class.java)
    private val menuItems = HomeMenuItem.primary
    private val dashboardRenderer by lazy { HomeDashboardRenderer(carContext) }
    private val handler = Handler(Looper.getMainLooper())
    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfaceDpi = 0
    private var stableArea: Rect? = null
    private var visibleArea: Rect? = null
    private var dashboardLayout: HomeDashboardLayout? = null
    /** The sections to draw, re-read whenever this screen comes back to the front. */
    private var content = HomeDashboardContent()
    private var scroll = 0f
    private var pressed: HomeHit? = null
    private var active = false

    /** Repaints the Now Playing card as the live session plays, pauses and moves on. */
    private val scope = MainScope()
    private var sessionWatch: Job? = null

    /**
     * Thumbnails for the hero and the rows.
     *
     * A cached still is handed straight to the renderer; one that has to be fetched arrives later
     * and repaints the dashboard once, which is why this is a callback and not a blocking read.
     * Everything without a still keeps its fallback artwork for good.
     */
    private val thumbnails = HomeDashboardRenderer.Thumbnails { url ->
        CarThumbnails.bitmap(carContext, url) { renderMenu() }
    }

    /** onClick reaches a SurfaceCallback from Car API 5; older hosts keep the host-drawn grid. */
    private val drawsMenu: Boolean by lazy { page == 0 && carContext.carAppApiLevel >= 5 }

    init {
        dev.autobridge.apps.QuickLaunchStore.seedDefaultsIfNeeded(carContext)
        maybeResumeLastSession()
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (!drawsMenu) return
                active = true
                pressed = null
                refreshContent()
                MirrorSurfaceOwnership.claim(this@CarHomeDashboardScreen)
                appManager.setSurfaceCallback(this@CarHomeDashboardScreen)
                sessionWatch = scope.launch { AutoBridgeSessionManager.state.collect { renderMenu() } }
                // The surface may have survived the screen above (both are surface templates).
                renderMenu()
            }

            override fun onStop(owner: LifecycleOwner) {
                if (!drawsMenu) return
                active = false
                sessionWatch?.cancel()
                sessionWatch = null
                handler.removeCallbacksAndMessages(null)
                releaseSurface()
                if (MirrorSurfaceOwnership.release(this@CarHomeDashboardScreen)) appManager.setSurfaceCallback(null)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                vehicleStateSession.close()
                speedSubscription.close()
                scope.cancel()
            }
        })
    }

    override fun onGetTemplate(): Template = if (drawsMenu) menuTemplate() else gridTemplate()

    // --- Surface-drawn home menu ---

    private fun menuTemplate(): Template =
        // NavigationTemplate requires a non-empty action strip; its one icon-only action is the
        // way to every secondary destination. Never Action.APP_ICON here (see CarBrowserScreen).
        NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setIcon(CarIcons.of(carContext, CarIcons.APPS))
                            .setOnClickListener { openMore() }
                            .build()
                    )
                    .build()
            )
            .build()

    private fun openMore() {
        screenManager.push(CarHomeMoreScreen(carContext, ::requestSafety))
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this)) return
        val next = surfaceContainer.surface ?: return
        if (next !== surface) releaseSurface()
        surface = next
        surfaceWidth = surfaceContainer.width
        surfaceHeight = surfaceContainer.height
        surfaceDpi = surfaceContainer.dpi
        dashboardLayout = null
        StructuredLog.i(TAG, "surface ${surfaceWidth}x$surfaceHeight dpi=$surfaceDpi")
        renderMenu()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        if (!MirrorSurfaceOwnership.isOwner(this)) return
        releaseSurface()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this)) return
        StructuredLog.i(TAG, "stable area $stableArea")
        this.stableArea = Rect(stableArea)
        dashboardLayout = null
        renderMenu()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this)) return
        StructuredLog.i(TAG, "visible area $visibleArea")
        this.visibleArea = Rect(visibleArea)
        dashboardLayout = null
        renderMenu()
    }

    override fun onClick(x: Float, y: Float) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this) || pressed != null) return
        val layout = dashboardLayout ?: return
        val hit = layout.hit(x, y, scroll) ?: return
        when (hit.region) {
            HomeRegion.SCROLL_UP -> return scrollMenuBy(-layout.scrollStep)
            HomeRegion.SCROLL_DOWN -> return scrollMenuBy(layout.scrollStep)
            else -> Unit
        }
        val action = actionFor(hit) ?: return

        // Short pressed flash on whatever was touched, then the action it stands for.
        pressed = hit
        renderMenu()
        handler.postDelayed({
            pressed = null
            if (!active) return@postDelayed
            action()
            // An action that opened something has already handed the surface over and this draws
            // nothing; one that could not (an address no engine accepts) leaves the home intact,
            // and this is what takes the pressed flash back off it.
            renderMenu()
        }, HomeDashboardTheme.PRESS_FEEDBACK_MS)
    }

    /**
     * What a tap does, or null when there is nothing behind it.
     *
     * Each action is responsible for releasing the surface *if* it hands it to another producer:
     * the browser, video and player screens attach their own (VirtualDisplay / decoder) and cannot
     * while this screen is still connected. Releasing unconditionally would blank the home on the
     * one path that stays here, which is a send the router refuses.
     */
    private fun actionFor(hit: HomeHit): (() -> Unit)? = when (hit.region) {
        HomeRegion.CONTINUE -> content.continueWatching?.let { item ->
            {
                if (handOverSurface(item.source)) {
                    dev.autobridge.bridge.AutoBridgeSessionManager.resume(carContext, item.snapshot)
                }
            }
        }

        // The buttons act on the live session in place when the card is the one playing; when it is
        // only a resumable snapshot, previous and play resume it, exactly as tapping the card does.
        HomeRegion.CONTROL_PLAY -> content.continueWatching?.let { item ->
            if (isLive(item)) {
                { AutoBridgeSessionManager.togglePlayPause() }
            } else actionFor(HomeHit(HomeRegion.CONTINUE))
        }

        HomeRegion.CONTROL_PREVIOUS -> content.continueWatching?.let { item ->
            if (isLive(item)) {
                { AutoBridgeSessionManager.previous() }
            } else actionFor(HomeHit(HomeRegion.CONTINUE))
        }

        // Next plays the head of the queue, the same route as a queue tap: there is no other
        // "next" to have (the queue is consumed as it plays).
        HomeRegion.CONTROL_NEXT -> content.queue.firstOrNull()?.let { item ->
            {
                val source = BridgeSource(item.url, item.title, origin = BridgeSource.Origin.QUEUE)
                if (handOverSurface(source)) {
                    AutoBridgeSessionManager.queueRemove(carContext, item.url)
                    AutoBridgeSessionManager.open(carContext, source)
                }
            }
        }

        HomeRegion.QUICK_ACCESS -> menuItems.getOrNull(hit.index)?.let { item ->
            {
                focusedSection = item.section
                releaseSurface()
                CarHomeNavigator.open(carContext, screenManager, item.section, ::requestSafety)
            }
        }

        HomeRegion.RECENT_HEADER -> {
            {
                releaseSurface()
                CarNavigation.open(screenManager, "CarRecentScreen") { CarRecentScreen(carContext) }
            }
        }

        HomeRegion.RECENT_ITEM -> content.recentlySent.getOrNull(hit.index)?.let { item ->
            { play(BridgeSource(item.url, item.title, origin = BridgeSource.Origin.CAR)) }
        }

        HomeRegion.QUEUE_HEADER -> {
            {
                releaseSurface()
                CarNavigation.open(screenManager, "CarQueueScreen") { CarQueueScreen(carContext) }
            }
        }

        HomeRegion.SCROLL_UP, HomeRegion.SCROLL_DOWN -> null
    }

    /** The card is the session the bridge is playing right now, not just the last one saved. */
    private fun isLive(item: ContinueItem): Boolean {
        val live = AutoBridgeSessionManager.current
        return live.engine != EngineKind.UNSUPPORTED &&
            live.source?.url == item.source.url &&
            live.playback in LIVE_STATES
    }

    /** The content to draw, with the live position when the card is the session playing now. */
    private fun liveContent(): HomeDashboardContent {
        val item = content.continueWatching ?: return content
        if (!isLive(item)) return content
        val live = AutoBridgeSessionManager.current
        return content.copy(
            continueWatching = item.copy(
                positionMs = live.positionMs,
                durationMs = if (live.durationMs > 0L) live.durationMs else item.durationMs
            )
        )
    }

    private fun play(source: BridgeSource) {
        if (!handOverSurface(source)) return
        dev.autobridge.bridge.AutoBridgeSessionManager.open(carContext, source)
    }

    /**
     * Gives the surface up for [source], or says no and keeps the home on screen.
     *
     * The router is asked first because a refusal pushes no screen: without this check the home
     * would release its producer, nothing would take it, and the head unit would go black with no
     * way back but the action strip.
     */
    private fun handOverSurface(source: BridgeSource): Boolean {
        val decision = dev.autobridge.bridge.ContentRouter.explain(
            source,
            dev.autobridge.remotestream.RemoteStreamConfig.isAvailable(carContext)
        )
        if (decision.engine == dev.autobridge.bridge.EngineKind.UNSUPPORTED) {
            StructuredLog.w(TAG, "refused ${source.url}: ${decision.reason}")
            CarToast.makeText(
                carContext,
                carContext.getString(
                    decision.error?.messageRes ?: R.string.bridge_error_unsupported
                ),
                CarToast.LENGTH_LONG
            ).show()
            return false
        }
        releaseSurface()
        return true
    }

    /** Re-reads the stores the dashboard draws from; the layout is sized by them, so it goes too. */
    private fun refreshContent() {
        content = HomeDashboardContent.read(carContext)
        dashboardLayout = null
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this)) return
        scrollMenuBy(distanceY)
    }

    private fun scrollMenuBy(delta: Float) {
        val layout = dashboardLayout ?: return
        if (!layout.scrollable) return
        scroll = (scroll + delta).coerceIn(0f, layout.maxScroll)
        renderMenu()
    }

    /** The area the menu may use: the host's stable area, else its visible area, else everything. */
    private fun safeArea(): MenuBox {
        val full = Rect(0, 0, surfaceWidth, surfaceHeight)
        val inset = listOfNotNull(stableArea, visibleArea)
            .firstOrNull { !it.isEmpty && full.contains(it) && it.width() > surfaceWidth / 2 && it.height() > surfaceHeight / 2 }
            ?: full
        return MenuBox(inset.left.toFloat(), inset.top.toFloat(), inset.right.toFloat(), inset.bottom.toFloat())
    }

    private fun renderMenu() {
        val target = surface ?: return
        if (!active || !MirrorSurfaceOwnership.isOwner(this) || !target.isValid) return
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        // The head unit's dpi, never the phone's: it decides every dp on this screen.
        val density = (if (surfaceDpi > 0) surfaceDpi else BASELINE_DPI) / BASELINE_DPI.toFloat()
        val layout = dashboardLayout ?: HomeDashboardLayout.compute(
            safeArea(), density, menuItems.map { it.title(carContext) }, content,
            dashboardRenderer::measureLabel,
            recentTitle = carContext.getString(R.string.car_home_recent_from_phone),
            queueLink = content.queueTotal.takeIf { it > 0 }?.let(dashboardRenderer::queueLink)
        ).also {
            dashboardLayout = it
            scroll = scroll.coerceIn(0f, it.maxScroll)
            StructuredLog.i(
                TAG,
                "layout card=${it.cards.first().width.toInt()}x${it.cards.first().height.toInt()}" +
                    " hero=${it.continueCard != null} recent=${it.recentRows.size}" +
                    " queue=${content.queueTotal} scroll=${it.scrollable}"
            )
        }
        // Not lockHardwareCanvas directly: on some devices it takes the process down a frame
        // later, uncatchably. See [dev.autobridge.display.CarSurfaceCanvas].
        dev.autobridge.display.CarSurfaceCanvas.draw(carContext, target) { canvas ->
            val focused = menuItems.indexOfFirst { it.section == focusedSection }
            val playing = content.continueWatching?.let { isLive(it) } == true &&
                AutoBridgeSessionManager.current.isPlaying
            dashboardRenderer.draw(
                canvas, layout, menuItems, liveContent(),
                HomeDashboardRenderer.State(focused, pressed, scroll, playing, nextEnabled = content.hasQueue),
                thumbnails
            )
        }
    }

    /** Drops our producer connection to the surface; the host still owns the surface itself. */
    private fun releaseSurface() {
        surface?.let { runCatching { it.release() } }
        surface = null
    }

    // --- Host-drawn grid, kept for hosts below Car API 5 and for any later page ---

    /**
     * The grid's running order: the six home cards, then what a driver reaches for next.
     *
     * Page one deliberately matches the surface-drawn cards, so a host that falls back to the grid
     * shows the same home. After that [HomeSection]'s own order put Mirror and Quick Launch (APPS)
     * at positions 12 and 13 of 14 — three pages in, on a head unit that fits five tiles a page.
     * They are promoted here rather than by re-ordering the enum, which the phone launcher reads
     * too and where the current order is right.
     */
    private fun gridOrder(): List<HomeSection> {
        val lead = HomeMenuItem.primary.map { it.section } +
            listOf(HomeSection.MIRROR, HomeSection.APPS)
        return lead + HomeSection.carSections.filterNot { it in lead }
    }

    private fun gridTemplate(): Template {
        val sections = gridOrder()
        // Head units cap grid items (commonly six). One slot on every page but the last is spent on
        // "More", so the whole home fits without a template the host would reject.
        val limit = runCatching {
            carContext.getCarService(androidx.car.app.constraints.ConstraintManager::class.java)
                .getContentLimit(androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_GRID)
        }.getOrDefault(6).coerceIn(2, 12)
        val pageSize = limit - 1
        val start = (page * pageSize).coerceAtMost(sections.size)
        val visible = sections.drop(start).take(pageSize)
        val hasMore = start + visible.size < sections.size
        val grid = ItemList.Builder()
        visible.forEach { section ->
            grid.addItem(
                GridItem.Builder()
                    .setTitle(section.title(carContext))
                    // IMAGE_TYPE_ICON tells the host to tint the bitmap as a monochrome mask, which
                    // flattens this custom multi-color glyph+card artwork into a blank tinted square.
                    // IMAGE_TYPE_LARGE preserves the actual pixel colors.
                    .setImage(DashboardArtwork.icon(artwork(section), compact = true), GridItem.IMAGE_TYPE_LARGE)
                    .setOnClickListener { CarHomeNavigator.open(carContext, screenManager, section, ::requestSafety) }
                    .build()
            )
        }
        grid.addItem(
            GridItem.Builder()
                // Always "More": the last page already carries its own Settings tile, and a second
                // tile with the same label going somewhere else is just a trap.
                .setTitle(carContext.getString(R.string.car_home_more))
                .setImage(
                    DashboardArtwork.icon(DashboardArtwork.Kind.MORE, compact = true),
                    GridItem.IMAGE_TYPE_LARGE
                )
                .setOnClickListener {
                    if (hasMore) screenManager.push(CarHomeDashboardScreen(carContext, page + 1))
                    else openMore()
                }
                .build()
        )
        val template = GridTemplate.Builder().setSingleList(grid.build())
        val title =
            if (page == 0) carContext.getString(R.string.app_name)
            else carContext.getString(R.string.car_home_title_paged, page + 1)
        // Keep the header free of action buttons. Older hosts use the legacy header API.
        if (carContext.carAppApiLevel >= 7) {
            template.setHeader(Header.Builder().setTitle(title)
                .setStartHeaderAction(if (page == 0) Action.APP_ICON else Action.BACK).build())
        } else {
            @Suppress("DEPRECATION")
            template.setTitle(title).setHeaderAction(if (page == 0) Action.APP_ICON else Action.BACK)
        }
        if (carContext.carAppApiLevel >= 8) {
            template.setItemSize(GridTemplate.ITEM_SIZE_SMALL)
                .setItemImageShape(GridTemplate.ITEM_IMAGE_SHAPE_UNSET)
        }
        return template.build()
    }

    private fun artwork(section: HomeSection): DashboardArtwork.Kind =
        when (section) {
            HomeSection.TV -> DashboardArtwork.Kind.TV
            HomeSection.RADIO -> DashboardArtwork.Kind.RADIO
            HomeSection.WEB -> DashboardArtwork.Kind.BROWSER
            HomeSection.YOUTUBE -> DashboardArtwork.Kind.YOUTUBE
            HomeSection.YOUTUBE_MUSIC -> DashboardArtwork.Kind.YOUTUBE_MUSIC
            HomeSection.STREAMING -> DashboardArtwork.Kind.STREAMING
            HomeSection.FOLDERS -> DashboardArtwork.Kind.FOLDERS
            HomeSection.FAVORITES -> DashboardArtwork.Kind.FAVORITES
            HomeSection.PLAYLISTS -> DashboardArtwork.Kind.PLAYLISTS
            HomeSection.GALLERY -> DashboardArtwork.Kind.GALLERY
            HomeSection.WEATHER -> DashboardArtwork.Kind.WEATHER
            HomeSection.MIRROR -> DashboardArtwork.Kind.MIRROR
            HomeSection.APPS -> DashboardArtwork.Kind.QUICK_LAUNCH
            HomeSection.REMOTE -> DashboardArtwork.Kind.AGENT
            HomeSection.SETTINGS -> DashboardArtwork.Kind.SETTINGS
        }

    private fun requestSafety() {
        vehicleStateSession.requestPermission { granted ->
            CarToast.makeText(
                carContext,
                carContext.getString(if (granted) R.string.car_home_speed_safety_enabled else R.string.car_home_speed_permission_denied),
                CarToast.LENGTH_SHORT
            ).show()
            invalidate()
        }
    }

    /**
     * Optional "Resume last session": if enabled and a recent activity exists within a freshness
     * window, push its screen on top of Home so the user lands where they left off. Runs once per
     * process launch. It only resumes into existing screens/actions (never fabricates state) and is
     * validated by kind + a timeout, so stale/invalid sessions are ignored.
     */
    private fun maybeResumeLastSession() {
        if (resumedThisLaunch) return
        resumedThisLaunch = true
        if (!dev.autobridge.settings.AppPreferences.resumeLastSession(carContext)) return

        val latest = dev.autobridge.core.state.RecentActivityStore.list(carContext).firstOrNull() ?: return
        val ageMs = System.currentTimeMillis() - latest.timestampMs
        if (latest.timestampMs <= 0L || ageMs > RESUME_MAX_AGE_MS) return

        // Defer to the next frame so Home is the base of the back-stack before the resumed screen.
        carContext.mainExecutor.execute {
            val command = when (latest.kind) {
                dev.autobridge.core.state.RecentActivityStore.Kind.BROWSER ->
                    latest.data?.let {
                        dev.autobridge.agent.AgentCommandRouter.Command(
                            dev.autobridge.agent.AgentCommandRouter.AgentAction.OPEN_URL, it
                        )
                    }
                dev.autobridge.core.state.RecentActivityStore.Kind.MEDIA ->
                    dev.autobridge.agent.AgentCommandRouter.Command(
                        dev.autobridge.agent.AgentCommandRouter.AgentAction.RESUME_MEDIA
                    )
                dev.autobridge.core.state.RecentActivityStore.Kind.MIRROR ->
                    dev.autobridge.agent.AgentCommandRouter.Command(
                        dev.autobridge.agent.AgentCommandRouter.AgentAction.OPEN_MIRROR
                    )
                dev.autobridge.core.state.RecentActivityStore.Kind.AGENT -> null
            } ?: return@execute
            dev.autobridge.agent.AgentCommandRouter.execute(this, carContext, command)
        }
    }

    private companion object {
        const val RESUME_MAX_AGE_MS = 60L * 60L * 1000L // 1 hour freshness window
        @Volatile
        var resumedThisLaunch = false
        const val TAG = "CarHome"
        const val BASELINE_DPI = 160

        /** Session states in which the Now Playing buttons can act on the engine directly. */
        val LIVE_STATES = setOf(BridgePlaybackState.LOADING, BridgePlaybackState.PLAYING, BridgePlaybackState.PAUSED)

        /**
         * Last card the driver opened. Drawn in the focused style when Home comes back, so the
         * driver sees where they were; surface content cannot take real rotary focus.
         */
        var focusedSection: HomeSection? = null
    }
}
