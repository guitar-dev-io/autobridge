package dev.autobridge.car

import android.graphics.Rect
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.browser.BrowserUserAgentMode
import dev.autobridge.browser.BrowserUserAgentStore
import dev.autobridge.browser.CarBrowserRuntime
import dev.autobridge.browser.CarWebRenderer
import dev.autobridge.browser.ViewportDebug
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.mirror.MirrorSurfaceOwnership
import dev.autobridge.mirror.ProjectionService
import dev.autobridge.remote.AutoBridgeStateRepository
import dev.autobridge.remote.CarScreenController
import dev.autobridge.safety.ParkingStateStore

/**
 * Native Android Auto web browser, AA-Browser-style: a [CarWebRenderer] draws a WebView directly
 * onto the car surface and receives touch/scroll from this screen's [SurfaceCallback]. No
 * MediaProjection mirror and no accessibility/Shizuku input backend are involved.
 *
 * The screen claims the shared surface callback through [MirrorSurfaceOwnership] so it never fights
 * the mirror screen for the same Android Auto surface.
 *
 * Browser chrome — toolbar, drawer, tab switcher — is drawn by the renderer on the same surface as
 * the page, so opening it never detaches the surface and never re-measures the WebView. Only the
 * destinations that genuinely need a car template (keyboard input, list pickers, settings) are
 * pushed as screens, and those still keep the renderer and its page alive underneath.
 */
class CarBrowserScreen(carContext: CarContext) :
    Screen(carContext), SurfaceCallback, CarScreenController.BrowserTarget, CarWebRenderer.Host {
    private companion object {
        const val TAG = "AutoBridgeCarBrowser"
    }

    private val appManager = carContext.getCarService(AppManager::class.java)

    /**
     * Session-scoped, not screen-scoped: returning to the browser must find the same page, history
     * and tabs it was left with.
     */
    private val renderer = CarBrowserRuntime.renderer(carContext)
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfaceDpi = 0

    private var active = false
    private fun allowed() = ParkingStateStore.isParked && FeaturePolicy.app.isAvailable(Feature.BROWSER)
    private val parkingListener: (ParkingStateStore.State) -> Unit = {
        carContext.mainExecutor.execute {
            if (active && !allowed()) {
                renderer.stop()
                invalidate()
            }
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                active = true
                ParkingStateStore.addListener(parkingListener)
                ProjectionService.stop(carContext)
                MirrorSurfaceOwnership.claim(this@CarBrowserScreen)
                appManager.setSurfaceCallback(this@CarBrowserScreen)
                renderer.host = this@CarBrowserScreen
                renderer.onPageChanged = { url, title ->
                    carContext.mainExecutor.execute {
                        // Publish live browser state so the Mobile Remote reflects the car in real time.
                        AutoBridgeStateRepository.setBrowser(url, title, loading = renderer.isLoading)
                        invalidate()
                    }
                }
                // Google/Microsoft/Apple block sign-in inside any embedded WebView; hand off to the
                // real external browser instead of letting the car surface hit their block page.
                renderer.onExternalSignInRequired = { url ->
                    carContext.mainExecutor.execute {
                        CarToast.makeText(carContext, "เข้าสู่ระบบต้องใช้เบราว์เซอร์ภายนอก", CarToast.LENGTH_LONG).show()
                        openExternal(url)
                    }
                }
                // Mark this browser as the active target for mobile-issued browser commands.
                CarScreenController.activeBrowser = this@CarBrowserScreen
                AutoBridgeStateRepository.setBrowserActive(renderer.url, renderer.title)
            }

            override fun onStop(owner: LifecycleOwner) {
                active = false
                ParkingStateStore.removeListener(parkingListener)
                renderer.stop()
                if (renderer.host === this@CarBrowserScreen) {
                    renderer.host = null
                    // These lambdas capture this screen and call invalidate() on it. The renderer
                    // outlives the screen, so leaving them attached would let a popped screen be
                    // invalidated by a page event that belongs to its replacement.
                    renderer.onPageChanged = null
                    renderer.onExternalSignInRequired = null
                }
                if (CarScreenController.activeBrowser === this@CarBrowserScreen) {
                    CarScreenController.activeBrowser = null
                }
                if (MirrorSurfaceOwnership.release(this@CarBrowserScreen)) appManager.setSurfaceCallback(null)
            }

            // The renderer is deliberately NOT destroyed here. It belongs to the car session
            // (see CarBrowserRuntime), so navigating away and back preserves the page and tabs.
        })
    }

    // --- CarScreenController.BrowserTarget: lets the router drive the live browser in place ---
    override fun goBack(): Boolean {
        val can = renderer.canGoBack
        renderer.goBack()
        invalidate()
        return can
    }

    override fun goForward(): Boolean {
        val can = renderer.canGoForward
        renderer.goForward()
        invalidate()
        return can
    }

    override fun setFullscreen(enabled: Boolean) {
        renderer.setFullscreen(enabled)
        invalidate()
    }

    override fun setDesktopMode(enabled: Boolean) {
        BrowserUserAgentStore.select(
            carContext,
            if (enabled) BrowserUserAgentMode.DESKTOP else BrowserUserAgentMode.MOBILE
        )
        renderer.applyUserAgentAndReload()
        invalidate()
    }

    override fun sendTextToSearch(text: String, autoSubmit: Boolean) {
        renderer.submitText(text, autoSubmit)
        invalidate()
    }

    override val currentUrl: String get() = renderer.url

    override fun onGetTemplate(): Template {
        if (!allowed()) return PaneTemplate.Builder(
            Pane.Builder().addRow(
                Row.Builder().setTitle("Browser")
                    .addText("Park the vehicle to browse websites.").build()
            ).build()
        ).setHeader(Header.Builder().setTitle("Browser").setStartHeaderAction(Action.BACK).build()).build()
        // No dedicated back button and no second menu button: the on-canvas hamburger menu (☰,
        // already drawn by the renderer in its own toolbar) is the single entry point into browser
        // navigation — Home, Bookmarks, History, Settings, etc. Action.APP_ICON is a non-interactive
        // filler; the host requires a non-empty action strip, but nothing here should compete with
        // the canvas hamburger for the same job.
        return NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(Action.APP_ICON).build())
            .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
            .build()
    }

    // --- CarWebRenderer.Host: navigation performed via the existing ScreenManager ---

    /** Pushes a [CarBrowserSearchScreen] so the user can type a URL / query on the car display. */
    override fun openAddressInput() {
        screenManager.pushForResult(CarBrowserSearchScreen(carContext, renderer.url)) { result ->
            val query = result as? String ?: return@pushForResult
            if (query.isNotBlank()) openUrl(query)
        }
    }

    override fun openFindInPage() {
        // Ask for the term first, then open the find controls screen (prev/next + counter).
        screenManager.pushForResult(CarBrowserSearchScreen(carContext, renderer.findQuery)) { result ->
            val query = result as? String ?: return@pushForResult
            screenManager.push(CarBrowserFindScreen(carContext, renderer, query))
        }
    }

    override fun openBookmarks() {
        screenManager.pushForResult(CarBrowserBookmarksScreen(carContext)) { result ->
            (result as? String)?.let(::openUrl)
        }
    }

    override fun openHistory() {
        screenManager.pushForResult(CarBrowserHistoryScreen(carContext)) { result ->
            (result as? String)?.let(::openUrl)
        }
    }

    override fun openAgent() {
        // Real Agent surface (ask/search/control), not the User-Agent settings.
        CarNavigation.open(screenManager, "CarAgentScreen") { CarAgentScreen(carContext) }
    }

    override fun openDownloads() {
        screenManager.push(CarBrowserDownloadsScreen(carContext))
    }

    override fun openMediaCenter() {
        CarNavigation.open(screenManager, "CarMediaCenterScreen") { CarMediaCenterScreen(carContext) }
    }

    override fun openNowPlaying() {
        CarNavigation.open(screenManager, "CarNowPlayingScreen") { CarNowPlayingScreen(carContext) }
    }

    override fun openMediaLibrary() {
        CarNavigation.open(screenManager, "CarMediaLibraryScreen") { CarMediaLibraryScreen(carContext) }
    }

    override fun openSettings() {
        screenManager.pushForResult(CarBrowserSettingsScreen(carContext)) { changed ->
            if (changed == true) {
                renderer.applyUserAgentAndReload()
                CarToast.makeText(
                    carContext,
                    "User-Agent: ${BrowserUserAgentStore.label(carContext)}",
                    CarToast.LENGTH_SHORT
                ).show()
                invalidate()
            }
        }
    }

    override fun openDiagnostics() {
        CarNavigation.open(screenManager, "CarDiagnosticsScreen") { CarDiagnosticsScreen(carContext) }
    }

    override fun showMessage(text: String) {
        CarToast.makeText(carContext, text, CarToast.LENGTH_SHORT).show()
    }

    override fun onBrowserStateChanged() {
        invalidate()
    }

    override fun openExternal(url: String) {
        val safe = dev.autobridge.entertainment.ContentAddress.https(url) ?: url
        val opened = runCatching {
            carContext.startCarApp(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(safe)))
            true
        }.getOrDefault(false)
        if (!opened) {
            CarToast.makeText(carContext, "No external browser available", CarToast.LENGTH_SHORT).show()
        }
    }

    override fun reload() {
        renderer.reload()
        invalidate()
    }

    /** Public entry so the phone side or a quick link can push a URL into the car browser. */
    override fun openUrl(url: String) {
        renderer.load(url)
        invalidate()
    }

    // --- SurfaceCallback ---

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this)) return
        val surface = surfaceContainer.surface ?: return
        surfaceWidth = surfaceContainer.width
        surfaceHeight = surfaceContainer.height
        // The host's own dpi is the only correct density for car chrome; the connected phone's
        // density describes a different screen and would mis-size every icon.
        surfaceDpi = surfaceContainer.dpi
        Log.i(TAG, "Browser surface ${surfaceWidth}x$surfaceHeight dpi=$surfaceDpi")
        if (allowed()) renderer.start(surface, surfaceWidth, surfaceHeight, surfaceDpi)
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this)) return
        // The stable area is intentionally left as-is. Clearing it here used to re-measure the page
        // back to the full surface on teardown and again on re-attach, so every menu round-trip
        // reflowed the site twice.
        renderer.stop()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this)) return
        // Reserve host controls without reflowing whenever they temporarily hide or reappear; the
        // renderer applies its own epsilon so only a material change reaches the page.
        renderer.setStableArea(stableArea)
    }

    override fun onClick(x: Float, y: Float) {
        // Gated: this fires on every tap, which is useful while diagnosing input routing and pure
        // noise otherwise.
        if (ViewportDebug.enabled) {
            Log.i(
                TAG,
                "onClick $x,$y active=$active owner=${MirrorSurfaceOwnership.isOwner(this)} " +
                    "allowed=${allowed()} parked=${ParkingStateStore.isParked}"
            )
        }
        if (!active || !MirrorSurfaceOwnership.isOwner(this)) return
        if (!allowed()) return
        renderer.onSurfaceClick(x, y)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this)) return
        if (allowed()) renderer.scrollBy(distanceX, distanceY)
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this)) return
        if (allowed()) renderer.fling(velocityX, velocityY)
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this)) return
        if (allowed()) renderer.scaleBy(scaleFactor)
    }
}
