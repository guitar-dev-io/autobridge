package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.ScreenManager
import dev.autobridge.library.HomeSection

/**
 * Opens a home section. Shared by the surface-drawn home menu, its grid fallback and the "More"
 * list, so a section behaves the same whichever entry point the driver used.
 */
internal object CarHomeNavigator {
    fun open(
        carContext: CarContext,
        screenManager: ScreenManager,
        section: HomeSection,
        requestSafety: () -> Unit
    ) {
        dev.autobridge.logging.StructuredLog.i("CarHome", "tapped -> ${section.name}")
        // Exhaustive over HomeSection with no `else`: under Kotlin 2 (K2) a `when (section)`
        // statement that omits any enum constant is a compile error, so a new section can never
        // silently fall through to Settings — the compiler forces a branch to be added here.
        when (section) {
            HomeSection.TV ->
                screenManager.push(CarIptvSourcesScreen(carContext, dev.autobridge.iptv.IptvKind.TV))
            // Tabs and a logo grid from Car API 6; the list landing on an older host.
            HomeSection.RADIO ->
                screenManager.push(CarRadioHomeScreen.open(carContext))
            // The two web-video sections are just a website (HomeSection.webUrl is non-null for
            // exactly these two). Tapping a site tile that is already open means "take me back to
            // it", not "start over": loading the root again would throw the page away and restart
            // whatever is playing on it. See [dev.autobridge.browser.BrowserSiteEntry].
            HomeSection.YOUTUBE, HomeSection.YOUTUBE_MUSIC -> {
                val url = section.webUrl!!
                val renderer = dev.autobridge.browser.CarBrowserRuntime.renderer(carContext)
                if (!dev.autobridge.browser.BrowserSiteEntry.resumes(renderer.livePageUrl, url)) {
                    renderer.load(url)
                }
                CarNavigation.open(screenManager, "CarBrowserScreen") { CarBrowserScreen(carContext) }
            }
            // The browser opens on its own start page, as AABrowser does (search, support, quick
            // links, and the desktop browser when one is installed); a page already open is
            // returned to instead, so whatever is playing on it keeps playing.
            HomeSection.WEB -> {
                val renderer = dev.autobridge.browser.CarBrowserRuntime.renderer(carContext)
                if (renderer.livePageUrl?.startsWith("http") != true) {
                    renderer.load(dev.autobridge.browser.CarStartPage.URL)
                }
                CarNavigation.open(screenManager, "CarBrowserScreen") { CarBrowserScreen(carContext) }
            }
            HomeSection.FOLDERS ->
                screenManager.push(CarLibraryScreen(carContext, CarLibraryScreen.Mode.FOLDERS))
            HomeSection.PLAYLISTS ->
                screenManager.push(CarLibraryScreen(carContext, CarLibraryScreen.Mode.PLAYLISTS))
            HomeSection.GALLERY ->
                screenManager.push(CarLibraryScreen(carContext, CarLibraryScreen.Mode.GALLERY))
            HomeSection.FAVORITES ->
                screenManager.push(CarFavoritesScreen(carContext))
            // Drawn by the app, three cards to a row, where the host has a surface to draw on
            // (Car API 5); below that the host's own grid.
            HomeSection.STREAMING ->
                screenManager.push(
                    if (carContext.carAppApiLevel >= 5) CarStreamingSurfaceScreen(carContext) else CarStreamingScreen(carContext)
                )
            HomeSection.WEATHER ->
                screenManager.push(CarWeatherScreen(carContext))
            HomeSection.MIRROR ->
                screenManager.push(CarMirrorIntroScreen(carContext))
            HomeSection.APPS ->
                CarNavigation.open(screenManager, "CarQuickLaunchScreen") { CarQuickLaunchScreen(carContext) }
            // The Agent is the car's remote-control surface ("control the car screen"); the same
            // "CarAgentScreen" marker is used by the More menu's Agent row and the driving-mode
            // tile, so routing REMOTE here returns to that instance instead of stacking a copy.
            HomeSection.REMOTE ->
                CarNavigation.open(screenManager, "CarAgentScreen") { CarAgentScreen(carContext) }
            // Explicit branch (was previously reached only via the removed `else`); the voice
            // "open settings" path (VoiceScreen.SETTINGS) relies on this.
            HomeSection.SETTINGS ->
                CarNavigation.open(screenManager, "CarSettingsScreen") { CarSettingsScreen(carContext, requestSafety) }
        }
    }
}
