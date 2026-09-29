package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.core.state.VehicleStateSession
import dev.autobridge.mirror.ProjectionService

/**
 * AutoBridge Home Dashboard.
 *
 * Reference-inspired feature cards, with the tile layout, captions and touch targets owned by the
 * Android Auto host. This keeps the dashboard readable at different head-unit resolutions.
 *
 * This screen only navigates; each feature owns its own state, so returning here never destroys a
 * browser page, media playback, or mirror session.
 */
class CarHomeDashboardScreen(
    carContext: CarContext,
    private val page: Int = 0
) : Screen(carContext) {
    private val speedSubscription = dev.autobridge.speed.SpeedManager.acquire(carContext)
    private val vehicleStateSession = VehicleStateSession.acquire(carContext) {
        ProjectionService.stop(carContext)
        carContext.finishCarApp()
    }
    init {
        dev.autobridge.apps.QuickLaunchStore.seedDefaultsIfNeeded(carContext)
        maybeResumeLastSession()
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                vehicleStateSession.close()
                speedSubscription.close()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val sections = dev.autobridge.library.HomeSection.carSections
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
                    .setTitle(section.title)
                    // IMAGE_TYPE_ICON tells the host to tint the bitmap as a monochrome mask, which
                    // flattens this custom multi-color glyph+card artwork into a blank tinted square.
                    // IMAGE_TYPE_LARGE preserves the actual pixel colors.
                    .setImage(DashboardArtwork.icon(artwork(section), compact = true), GridItem.IMAGE_TYPE_LARGE)
                    .setOnClickListener { open(section) }
                    .build()
            )
        }
        grid.addItem(
            GridItem.Builder()
                // Always "More": the last page already carries its own Settings tile, and a second
                // tile with the same label going somewhere else is just a trap.
                .setTitle("More")
                .setImage(
                    DashboardArtwork.icon(DashboardArtwork.Kind.MORE, compact = true),
                    GridItem.IMAGE_TYPE_LARGE
                )
                .setOnClickListener {
                    if (hasMore) screenManager.push(CarHomeDashboardScreen(carContext, page + 1))
                    else screenManager.push(CarHomeMoreScreen(carContext, ::requestSafety))
                }
                .build()
        )

        val template = GridTemplate.Builder().setSingleList(grid.build())
        val title = if (page == 0) "AutoBridge" else "AutoBridge · ${page + 1}"
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

    private fun artwork(section: dev.autobridge.library.HomeSection): DashboardArtwork.Kind =
        when (section) {
            dev.autobridge.library.HomeSection.TV -> DashboardArtwork.Kind.TV
            dev.autobridge.library.HomeSection.RADIO -> DashboardArtwork.Kind.RADIO
            dev.autobridge.library.HomeSection.WEB -> DashboardArtwork.Kind.BROWSER
            dev.autobridge.library.HomeSection.YOUTUBE -> DashboardArtwork.Kind.YOUTUBE
            dev.autobridge.library.HomeSection.YOUTUBE_MUSIC -> DashboardArtwork.Kind.YOUTUBE_MUSIC
            dev.autobridge.library.HomeSection.YOUTUBE_KIDS -> DashboardArtwork.Kind.YOUTUBE_KIDS
            dev.autobridge.library.HomeSection.FOLDERS -> DashboardArtwork.Kind.FOLDERS
            dev.autobridge.library.HomeSection.FAVORITES -> DashboardArtwork.Kind.FAVORITES
            dev.autobridge.library.HomeSection.PLAYLISTS -> DashboardArtwork.Kind.PLAYLISTS
            dev.autobridge.library.HomeSection.GALLERY -> DashboardArtwork.Kind.GALLERY
            dev.autobridge.library.HomeSection.MIRROR -> DashboardArtwork.Kind.MIRROR
            dev.autobridge.library.HomeSection.APPS -> DashboardArtwork.Kind.QUICK_LAUNCH
            dev.autobridge.library.HomeSection.REMOTE -> DashboardArtwork.Kind.AGENT
            dev.autobridge.library.HomeSection.SETTINGS -> DashboardArtwork.Kind.SETTINGS
        }

    private fun open(section: dev.autobridge.library.HomeSection) {
        dev.autobridge.display.StructuredLog.i("CarHome", "tapped -> ${section.name}")
        val url = section.webUrl
        when {
            section == dev.autobridge.library.HomeSection.TV ->
                screenManager.push(CarIptvSourcesScreen(carContext, dev.autobridge.iptv.IptvKind.TV))
            section == dev.autobridge.library.HomeSection.RADIO ->
                screenManager.push(CarIptvSourcesScreen(carContext, dev.autobridge.iptv.IptvKind.RADIO))
            url != null -> {
                dev.autobridge.browser.CarBrowserRuntime.renderer(carContext).load(url)
                CarNavigation.open(screenManager, "CarBrowserScreen") { CarBrowserScreen(carContext) }
            }
            section == dev.autobridge.library.HomeSection.WEB ->
                CarNavigation.open(screenManager, "CarBrowserScreen") { CarBrowserScreen(carContext) }
            section == dev.autobridge.library.HomeSection.FOLDERS ->
                screenManager.push(CarLibraryScreen(carContext, CarLibraryScreen.Mode.FOLDERS))
            section == dev.autobridge.library.HomeSection.PLAYLISTS ->
                screenManager.push(CarLibraryScreen(carContext, CarLibraryScreen.Mode.PLAYLISTS))
            section == dev.autobridge.library.HomeSection.GALLERY ->
                screenManager.push(CarLibraryScreen(carContext, CarLibraryScreen.Mode.GALLERY))
            section == dev.autobridge.library.HomeSection.FAVORITES ->
                screenManager.push(CarFavoritesScreen(carContext))
            section == dev.autobridge.library.HomeSection.MIRROR ->
                screenManager.push(CarMirrorIntroScreen(carContext))
            section == dev.autobridge.library.HomeSection.APPS ->
                CarNavigation.open(screenManager, "CarQuickLaunchScreen") { CarQuickLaunchScreen(carContext) }
            else -> CarNavigation.open(screenManager, "CarSettingsScreen") { CarSettingsScreen(carContext, ::requestSafety) }
        }
    }

    private fun requestSafety() {
        vehicleStateSession.requestPermission { granted ->
            CarToast.makeText(
                carContext,
                if (granted) "Speed safety enabled" else "Speed permission denied",
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
    }
}
