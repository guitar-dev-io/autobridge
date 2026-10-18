package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.ScreenManager
import dev.autobridge.R
import dev.autobridge.desktop.DesktopEngine
import dev.autobridge.duoscreen.car.DuoScreenScreen
import dev.autobridge.projection.desktop.DesktopBrowserCarScreen
import dev.autobridge.projection.desktop.DesktopEngineChoiceCarScreen

/**
 * The sideload flavors' car extensions: features that used to have Android Auto launcher entries
 * of their own are opened from inside AutoBridge instead, so AutoBridge is the one Android Auto app.
 *
 *  - Multi-app (Duo Screen) is pushed onto AutoBridge's own screen stack, embedded, so its Exit
 *    comes back here. Its separate car app service is disabled in :duoscreen's manifest.
 *  - The Web card opens the desktop engine (Linux Chrome or Brave, :desktopbrowser) when one is
 *    chosen *and* installed; otherwise it falls through to the built-in browser, unchanged. The
 *    separate "Desktop Browser" car app service is disabled in src/projection/AndroidManifest.xml.
 *  - "Browser engine" switches between them: the one browser row added, so the car still has a
 *    single way into the browser rather than one per engine.
 *
 * Bridge Web, the projection route, has no row: it is a projection activity, which a template app
 * cannot open. Its entry is unlisted by default and can be listed again from its settings page.
 */
internal object CarExtensions {
    fun moreEntries(carContext: CarContext, screenManager: ScreenManager): List<CarExtensionEntry> {
        val entries = mutableListOf(
            CarExtensionEntry(
                carContext.getString(R.string.car_more_multi_app),
                CarExtensionEntry.Placement.FREQUENT
            ) {
                CarNavigation.open(screenManager, "DuoScreenScreen") { DuoScreenScreen(carContext, embedded = true) }
            }
        )
        if (desktopSupported(carContext)) {
            val engine = DesktopEngine.chosenInstalledBrowser()?.displayName
                ?: carContext.getString(R.string.desktop_settings_choice_autobridge)
            entries += CarExtensionEntry(
                carContext.getString(R.string.car_more_browser_engine, engine),
                CarExtensionEntry.Placement.CONTENT
            ) {
                CarNavigation.open(screenManager, "DesktopEngineChoiceCarScreen") { DesktopEngineChoiceCarScreen(carContext) }
            }
        }
        return entries
    }

    fun openWeb(carContext: CarContext, screenManager: ScreenManager): Boolean {
        if (!desktopSupported(carContext) || DesktopEngine.chosenInstalledBrowser() == null) return false
        CarNavigation.open(screenManager, "DesktopBrowserCarScreen") { DesktopBrowserCarScreen(carContext, embedded = true) }
        return true
    }

    /** An arm64 phone and a build that ships PRoot; on anything else the engine rows stay hidden. */
    private fun desktopSupported(carContext: CarContext): Boolean = runCatching {
        DesktopEngine.init(carContext)
        DesktopEngine.supported
    }.getOrDefault(false)
}
