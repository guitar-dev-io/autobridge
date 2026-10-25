package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.ScreenManager
import dev.autobridge.R
import dev.autobridge.desktop.DesktopEngine
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dev.autobridge.desktop.browser.DesktopBrowsers
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
 *  - "Browser engine" (More) sets which browser the Web card opens.
 *  - Multi-app and Desktop browser also get cards of their own on the car home, beside the six
 *    primary ones, so neither is buried in More. The Desktop browser card opens the chosen desktop
 *    browser, or the first installed one, and the browser list when none is installed yet.
 *
 * Bridge Web, the projection route, has no row: it is a projection activity, which a template app
 * cannot open. Its entry is unlisted by default and can be listed again from its settings page.
 */
internal object CarExtensions {
    /**
     * Process start: the desktop browsers open on the same start page as the built-in car
     * browser. Only hands the engine the page builder; nothing is built until a browser launches.
     */
    fun install(context: android.content.Context) {
        val app = context.applicationContext
        DesktopEngine.startPages = { dev.autobridge.browser.CarStartPage.desktopPages(app) }
        // Thai pages follow the Thai letterforms chosen in Appearance (looped or not).
        DesktopEngine.thaiFontFamily = {
            if (dev.autobridge.settings.AppearanceStore.current().thai == dev.autobridge.design.ThaiStyle.LOOPED) {
                dev.autobridge.desktop.runtime.GuestFontConfig.LOOPED_THAI
            } else {
                dev.autobridge.desktop.runtime.GuestFontConfig.DEFAULT_THAI
            }
        }
        dev.autobridge.library.DesktopLinks.status = { ctx ->
            when {
                !desktopSupportedIn(ctx) -> dev.autobridge.library.DesktopLinks.ChromeStatus.UNAVAILABLE
                DesktopEngine.installedChrome() != null -> dev.autobridge.library.DesktopLinks.ChromeStatus.READY
                else -> dev.autobridge.library.DesktopLinks.ChromeStatus.MISSING
            }
        }
        dev.autobridge.library.DesktopLinks.setup = { ctx ->
            ctx.startActivity(
                dev.autobridge.projection.desktop.DesktopChromeSetupActivity.intent(ctx)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        // The phone's Streaming page sends the sites that may not play in the WebView here.
        dev.autobridge.library.DesktopLinks.opener = { ctx, url ->
            // Google Chrome for Linux, not whichever browser is chosen for the Web card: these sites
            // are meant for Chrome. Without it installed the built-in browser opens them.
            val browser = if (desktopSupportedIn(ctx)) DesktopEngine.installedChrome() else null
            browser?.let {
                ctx.startActivity(
                    dev.autobridge.projection.desktop.DesktopBrowserActivity.intent(ctx, it.id, url)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            } ?: false
        }
    }

    private fun desktopSupportedIn(context: android.content.Context): Boolean = runCatching {
        DesktopEngine.init(context)
        DesktopEngine.supported
    }.getOrDefault(false)

    fun moreEntries(carContext: CarContext, screenManager: ScreenManager): List<CarExtensionEntry> {
        // Multi-app's pane picker offers the desktop browser when it is installed.
        dev.autobridge.projection.desktop.DesktopPaneTarget.install(carContext)
        val entries = mutableListOf(
            CarExtensionEntry(
                carContext.getString(R.string.car_more_multi_app),
                CarExtensionEntry.Placement.FREQUENT,
                homeGlyph = HomeMenuGlyph.SPLIT,
                homeAccent = HomeDashboardTheme.ACCENT_MULTI_APP
            ) {
                CarNavigation.open(screenManager, "DuoScreenScreen") { DuoScreenScreen(carContext, embedded = true) }
            }
        )
        if (desktopSupported(carContext)) {
            entries += CarExtensionEntry(
                carContext.getString(dev.autobridge.desktop.R.string.desktop_engine_car_label),
                CarExtensionEntry.Placement.FREQUENT,
                homeGlyph = HomeMenuGlyph.DESKTOP,
                homeAccent = HomeDashboardTheme.ACCENT_DESKTOP
            ) { openDesktop(carContext, screenManager) }
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

    /**
     * The Desktop browser card: straight into a desktop browser when one is installed (the one
     * chosen for the Web card first), else the browser list, which opens the one picked.
     */
    private fun openDesktop(carContext: CarContext, screenManager: ScreenManager) {
        val browser = DesktopEngine.chosenInstalledBrowser()
            ?: DesktopBrowsers.all.firstOrNull { DesktopEngine.state(it.id).state.installed }
        if (browser == null) {
            CarNavigation.open(screenManager, "DesktopEngineChoiceCarScreen") {
                DesktopEngineChoiceCarScreen(carContext, openAfterChoice = true)
            }
            return
        }
        CarNavigation.open(screenManager, "DesktopBrowserCarScreen") {
            DesktopBrowserCarScreen(carContext, embedded = true, browser = browser)
        }
    }

    /** Whether [openWeb] has a desktop browser to open: the browser's start page offers it then. */
    fun canOpenWeb(carContext: CarContext): Boolean =
        desktopSupported(carContext) && DesktopEngine.chosenInstalledBrowser() != null

    fun openWeb(carContext: CarContext, screenManager: ScreenManager): Boolean {
        if (!canOpenWeb(carContext)) return false
        CarNavigation.open(screenManager, "DesktopBrowserCarScreen") { DesktopBrowserCarScreen(carContext, embedded = true) }
        return true
    }

    /** Whether Google Chrome for Linux is installed to open a site in; see [openUrl]. */
    fun canOpenChrome(carContext: CarContext): Boolean =
        desktopSupported(carContext) && DesktopEngine.installedChrome() != null

    /**
     * Whether this phone could run Chrome for Linux but does not have it yet: the case where a
     * protected-video site should send the user to install it rather than open a page that cannot play.
     */
    fun canInstallChrome(carContext: CarContext): Boolean =
        desktopSupported(carContext) && DesktopEngine.installedChrome() == null

    /**
     * Calls [onReady] on the main thread once Chrome for Linux is installed (the guided setup on the
     * phone finishing), so a car screen that sent the user there can carry on by itself. The
     * returned function stops watching; call it when the screen goes away.
     */
    fun watchChromeInstall(carContext: CarContext, onReady: () -> Unit): () -> Unit {
        if (!desktopSupported(carContext)) return {}
        val scope = kotlinx.coroutines.MainScope()
        scope.launch {
            DesktopEngine.browserStates.first { DesktopEngine.installedChrome() != null }
            onReady()
        }
        return { scope.cancel() }
    }

    /**
     * Opens [url] in Google Chrome for Linux on the car (not whichever browser is chosen for the
     * Web card), in a new tab when Chrome is already running. False when it is not installed: the
     * caller opens the built-in browser.
     */
    fun openUrl(carContext: CarContext, screenManager: ScreenManager, url: String): Boolean {
        if (!canOpenChrome(carContext)) return false
        val chrome = DesktopEngine.installedChrome() ?: return false
        CarNavigation.open(screenManager, "DesktopBrowserCarScreen") {
            DesktopBrowserCarScreen(carContext, embedded = true, browser = chrome, startUrl = url)
        }
        return true
    }

    /** An arm64 phone and a build that ships PRoot; on anything else the engine rows stay hidden. */
    private fun desktopSupported(carContext: CarContext): Boolean = runCatching {
        DesktopEngine.init(carContext)
        DesktopEngine.supported
    }.getOrDefault(false)
}
