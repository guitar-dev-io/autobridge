package dev.autobridge.projection.desktop

import android.content.Intent
import android.graphics.Rect
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
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
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.R
import dev.autobridge.car.CarBrowserSearchScreen
import dev.autobridge.car.CarIcons
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.desktop.DesktopEngine
import dev.autobridge.desktop.DesktopLog
import dev.autobridge.desktop.browser.DesktopBrowserAdapter
import dev.autobridge.desktop.input.Keysym
import dev.autobridge.desktop.session.DesktopSession
import dev.autobridge.safety.ParkingStateStore
import dev.autobridge.safety.SafetyEnforcement

/**
 * The desktop browser's own Android Auto entry ("Desktop Browser"). A separate CarAppService, like
 * Duo Screen's, so the experimental Linux runtime never shares a session with the template route
 * every flavor ships. It is a navigation-category app because only those receive a drawing
 * surface; Android Auto therefore shows it full screen in place of the navigation app.
 */
class DesktopBrowserCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR // Sideload/DHU builds only, same as the other car services.

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = DesktopBrowserCarScreen(carContext)
    }
}

/**
 * Shows the chosen desktop browser on the car surface and turns the host's gestures into mouse
 * input: tap → click, pan → wheel scroll, fling → a longer scroll, pinch → page zoom. Text goes
 * through the host's own keyboard (the search template), then into the focused field.
 *
 * Parked-only, enforced exactly like the built-in car browser: `SafetyEnforcement.gateParked` +
 * `FeaturePolicy` for [Feature.BROWSER]. While moving the page is replaced by a notice and input
 * is ignored; the browser itself keeps running (audio follows the media policy, as it does for
 * the built-in browser).
 */
class DesktopBrowserCarScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {
    private var session: DesktopSession? = null
    private val adapter: DesktopBrowserAdapter? = DesktopEngine.let { it.init(carContext); it.chosenInstalledBrowser() }
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private fun allowed(): Boolean =
        SafetyEnforcement.gateParked(ParkingStateStore.isParked) && FeaturePolicy.app.isAvailable(Feature.BROWSER)

    private val parkingListener: (ParkingStateStore.State) -> Unit = {
        applyGate()
        invalidate()
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                ParkingStateStore.addListener(parkingListener)
                if (adapter != null) carContext.getCarService(AppManager::class.java).setSurfaceCallback(this@DesktopBrowserCarScreen)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                ParkingStateStore.removeListener(parkingListener)
                session?.detachSurface()
                DesktopEngine.noteSurfaceDetached()
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
            }
        })
    }

    override fun onGetTemplate(): Template {
        val title = carContext.getString(dev.autobridge.desktop.R.string.desktop_engine_car_label)
        if (adapter == null) return message(title, carContext.getString(R.string.desktop_car_choose_browser))
        if (!allowed()) return message(title, carContext.getString(dev.autobridge.desktop.R.string.desktop_park_to_browse))
        return NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(action(R.drawable.ic_car_keyboard) { openKeyboard() })
                    .addAction(action(R.drawable.ic_browser_back) { session?.input?.back(); session?.renderer?.burst() })
                    .addAction(action(R.drawable.ic_browser_reload) { session?.input?.reload(); session?.renderer?.burst() })
                    .build()
            )
            .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
            .build()
    }

    private fun message(title: String, text: String): Template = PaneTemplate.Builder(
        Pane.Builder().addRow(Row.Builder().setTitle(title).addText(text).build()).build()
    ).setHeader(Header.Builder().setTitle(title).setStartHeaderAction(Action.APP_ICON).build()).build()

    private fun action(icon: Int, onClick: () -> Unit): Action =
        Action.Builder().setIcon(CarIcons.of(carContext, icon)).setOnClickListener(onClick).build()

    private fun openKeyboard() {
        screenManager.pushForResult(CarBrowserSearchScreen(carContext, "")) { result ->
            val text = result as? String ?: return@pushForResult
            session?.input?.typeText(text)
            session?.input?.key(Keysym.RETURN)
            session?.renderer?.burst()
        }
    }

    private fun applyGate() {
        session?.renderer?.blockedMessage =
            if (allowed()) null else carContext.getString(dev.autobridge.desktop.R.string.desktop_park_to_browse)
    }

    // ── SurfaceCallback ─────────────────────────────────────────────────────

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        val browser = adapter ?: return
        surfaceWidth = surfaceContainer.width
        surfaceHeight = surfaceContainer.height
        DesktopLog.i("car surface ${surfaceWidth}x$surfaceHeight dpi=${surfaceContainer.dpi}")
        val s = try {
            DesktopEngine.openSession(browser.id, surfaceWidth, surfaceHeight, surfaceContainer.dpi)
        } catch (e: Exception) {
            DesktopLog.w("car session failed", e)
            return
        }
        session = s
        s.renderer.placeholderText = carContext.getString(dev.autobridge.desktop.R.string.desktop_starting)
        applyGate()
        s.attachSurface(surface, surfaceWidth, surfaceHeight)
        DesktopEngine.noteSurfaceAttached()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        session?.detachSurface()
        DesktopEngine.noteSurfaceDetached()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        if (visibleArea.isEmpty || visibleArea.width() < surfaceWidth / 2 || visibleArea.height() < surfaceHeight / 2) return
        session?.setContentArea(visibleArea.left, visibleArea.top, visibleArea.right, visibleArea.bottom)
    }

    override fun onClick(x: Float, y: Float) {
        if (!allowed()) return
        val s = session ?: return
        val (dx, dy) = s.renderer.mapping?.toDisplay(x, y) ?: return
        s.input?.click(dx, dy)
        s.renderer.burst()
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (!allowed()) return
        val s = session ?: return
        val map = s.renderer.mapping ?: return
        val (cx, cy) = map.toDisplay(surfaceWidth / 2f, surfaceHeight / 2f)
        s.input?.scroll(cx, cy, map.toDisplayDistance(distanceX), map.toDisplayDistance(distanceY))
        s.renderer.burst()
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        if (!allowed()) return
        val s = session ?: return
        val map = s.renderer.mapping ?: return
        val (cx, cy) = map.toDisplay(surfaceWidth / 2f, surfaceHeight / 2f)
        s.input?.scroll(cx, cy, map.toDisplayDistance(-velocityX * 0.2f), map.toDisplayDistance(-velocityY * 0.2f))
        s.renderer.burst()
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (!allowed()) return
        val s = session ?: return
        val (dx, dy) = s.renderer.mapping?.toDisplay(focusX, focusY) ?: return
        s.input?.zoom(dx, dy, scaleFactor)
        s.renderer.burst()
    }
}
