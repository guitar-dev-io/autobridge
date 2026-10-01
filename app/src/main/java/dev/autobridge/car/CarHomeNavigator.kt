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
        dev.autobridge.display.StructuredLog.i("CarHome", "tapped -> ${section.name}")
        val url = section.webUrl
        when {
            section == HomeSection.TV ->
                screenManager.push(CarIptvSourcesScreen(carContext, dev.autobridge.iptv.IptvKind.TV))
            section == HomeSection.RADIO ->
                screenManager.push(CarIptvSourcesScreen(carContext, dev.autobridge.iptv.IptvKind.RADIO))
            url != null -> {
                // Tapping a site tile that is already open means "take me back to it", not "start
                // over": loading the root again would throw the page away and restart whatever is
                // playing on it. See [dev.autobridge.browser.CarBrowserEntry].
                val renderer = dev.autobridge.browser.CarBrowserRuntime.renderer(carContext)
                if (!dev.autobridge.browser.CarBrowserEntry.resumes(renderer.livePageUrl, url)) {
                    renderer.load(url)
                }
                CarNavigation.open(screenManager, "CarBrowserScreen") { CarBrowserScreen(carContext) }
            }
            section == HomeSection.WEB ->
                CarNavigation.open(screenManager, "CarBrowserScreen") { CarBrowserScreen(carContext) }
            section == HomeSection.FOLDERS ->
                screenManager.push(CarLibraryScreen(carContext, CarLibraryScreen.Mode.FOLDERS))
            section == HomeSection.PLAYLISTS ->
                screenManager.push(CarLibraryScreen(carContext, CarLibraryScreen.Mode.PLAYLISTS))
            section == HomeSection.GALLERY ->
                screenManager.push(CarLibraryScreen(carContext, CarLibraryScreen.Mode.GALLERY))
            section == HomeSection.FAVORITES ->
                screenManager.push(CarFavoritesScreen(carContext))
            section == HomeSection.STREAMING ->
                screenManager.push(CarStreamingScreen(carContext))
            section == HomeSection.WEATHER ->
                screenManager.push(CarWeatherScreen(carContext))
            section == HomeSection.MIRROR ->
                screenManager.push(CarMirrorIntroScreen(carContext))
            section == HomeSection.APPS ->
                CarNavigation.open(screenManager, "CarQuickLaunchScreen") { CarQuickLaunchScreen(carContext) }
            else -> CarNavigation.open(screenManager, "CarSettingsScreen") { CarSettingsScreen(carContext, requestSafety) }
        }
    }
}
