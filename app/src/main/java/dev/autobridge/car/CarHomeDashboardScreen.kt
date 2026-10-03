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
import dev.autobridge.core.state.VehicleStateSession
import dev.autobridge.display.StructuredLog
import dev.autobridge.library.HomeSection
import dev.autobridge.mirror.MirrorSurfaceOwnership
import dev.autobridge.mirror.ProjectionService

/**
 * AutoBridge Home Dashboard.
 *
 * The home is drawn by the app on the car surface (NavigationTemplate) as a 3 × 2 grid of
 * [HomeMenuCard]s - TV, Radio, Web browser, YouTube, YouTube Music, Streaming - under a compact
 * AutoBridge header, so it reads as one dark design system instead of host-styled tiles. Layout is
 * computed from the surface size, density and the host's stable area ([HomeMenuLayout]); Android
 * Auto's own rail and bottom bar sit outside the surface and are never drawn over.
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
    private val menuRenderer by lazy { HomeMenuRenderer(carContext) }
    private val handler = Handler(Looper.getMainLooper())
    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfaceDpi = 0
    private var stableArea: Rect? = null
    private var visibleArea: Rect? = null
    private var menuLayout: HomeMenuLayout? = null
    private var scroll = 0f
    private var pressed = -1
    private var active = false

    /** onClick reaches a SurfaceCallback from Car API 5; older hosts keep the host-drawn grid. */
    private val drawsMenu: Boolean by lazy { page == 0 && carContext.carAppApiLevel >= 5 }

    init {
        dev.autobridge.apps.QuickLaunchStore.seedDefaultsIfNeeded(carContext)
        maybeResumeLastSession()
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (!drawsMenu) return
                active = true
                pressed = -1
                MirrorSurfaceOwnership.claim(this@CarHomeDashboardScreen)
                appManager.setSurfaceCallback(this@CarHomeDashboardScreen)
                // The surface may have survived the screen above (both are surface templates).
                renderMenu()
            }

            override fun onStop(owner: LifecycleOwner) {
                if (!drawsMenu) return
                active = false
                handler.removeCallbacksAndMessages(null)
                releaseSurface()
                if (MirrorSurfaceOwnership.release(this@CarHomeDashboardScreen)) appManager.setSurfaceCallback(null)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                vehicleStateSession.close()
                speedSubscription.close()
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
        menuLayout = null
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
        menuLayout = null
        renderMenu()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this)) return
        StructuredLog.i(TAG, "visible area $visibleArea")
        this.visibleArea = Rect(visibleArea)
        menuLayout = null
        renderMenu()
    }

    override fun onClick(x: Float, y: Float) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this) || pressed >= 0) return
        val layout = menuLayout ?: return
        if (layout.scrollUp?.contains(x, y) == true) return scrollMenuBy(-layout.scrollStep)
        if (layout.scrollDown?.contains(x, y) == true) return scrollMenuBy(layout.scrollStep)
        val index = layout.cardAt(x, y, scroll)
        val item = menuItems.getOrNull(index) ?: return
        // Short pressed flash on the card, then the same navigation the grid tile performed.
        pressed = index
        focusedSection = item.section
        renderMenu()
        handler.postDelayed({
            pressed = -1
            if (!active) return@postDelayed
            // Hand the surface over cleanly: the browser and video screens attach their own
            // producers (VirtualDisplay / player) to it and cannot while we are still connected.
            releaseSurface()
            CarHomeNavigator.open(carContext, screenManager, item.section, ::requestSafety)
        }, HomeMenuTheme.PRESS_FEEDBACK_MS)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this)) return
        scrollMenuBy(distanceY)
    }

    private fun scrollMenuBy(delta: Float) {
        val layout = menuLayout ?: return
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
        val layout = menuLayout ?: HomeMenuLayout.compute(
            safeArea(), density, menuItems.map { it.title(carContext) }, menuRenderer::measureLabel
        ).also {
            menuLayout = it
            scroll = scroll.coerceIn(0f, it.maxScroll)
            StructuredLog.i(TAG, "layout card=${it.cards.first().width.toInt()}x${it.cards.first().height.toInt()} scroll=${it.scrollable}")
        }
        val canvas = runCatching { target.lockHardwareCanvas() }.getOrNull()
            ?: runCatching { target.lockCanvas(null) }.getOrNull()
            ?: return
        try {
            val focused = menuItems.indexOfFirst { it.section == focusedSection }
            menuRenderer.draw(canvas, layout, menuItems, HomeMenuRenderer.State(focused, pressed, scroll))
        } finally {
            runCatching { target.unlockCanvasAndPost(canvas) }
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

        /**
         * Last card the driver opened. Drawn in the focused style when Home comes back, so the
         * driver sees where they were; surface content cannot take real rotary focus.
         */
        var focusedSection: HomeSection? = null
    }
}
