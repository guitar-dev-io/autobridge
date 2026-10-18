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
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
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
import dev.autobridge.desktop.input.ControlRail
import dev.autobridge.desktop.input.Keysym
import dev.autobridge.desktop.input.PointerMode
import dev.autobridge.display.CarSurfaceCanvas
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
 * Shows the chosen desktop browser full screen on the car surface, with the floating control rail
 * on the left: touch mode (tap clicks, pan scrolls), mouse mode (pan moves the cursor, tap clicks
 * it — for small links), keyboard (the host's search keyboard, then typed into the page) and a
 * settings list (back, forward, reload, new tab, zoom).
 *
 * The browser window is sized to the car's visible area, so the page fills any head unit: 800×480,
 * 1280×720, 1920×720, portrait. Frames go through [CarSurfaceCanvas], which falls back to a
 * software canvas on devices whose hardware canvas aborts on car surfaces.
 *
 * Parked-only, enforced exactly like the built-in car browser: `SafetyEnforcement.gateParked` +
 * `FeaturePolicy` for [Feature.BROWSER]. While moving the page is replaced by a notice and input
 * is ignored; the browser itself keeps running (audio follows the media policy).
 */
class DesktopBrowserCarScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {
    private var session: DesktopSession? = null
    private val adapter: DesktopBrowserAdapter? = DesktopEngine.let { it.init(carContext); it.chosenInstalledBrowser() }
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfaceDpi = 160
    private var stableArea: Rect? = null
    private val rail = ControlRail(carContext, 1f)

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
        // The rail drawn on the surface is the control set; NavigationTemplate still needs a
        // non-empty action strip, so it carries the one action a driver reaches for most: back.
        // PAN in the map strip is what makes the host deliver pan/fling/zoom gestures.
        return NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(action(R.drawable.ic_browser_back) { session?.input?.back(); session?.renderer?.burst() })
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

    private fun openSettings() {
        screenManager.push(DesktopBrowserCarMenuScreen(carContext, { session }, rail))
    }

    private fun applyGate() {
        session?.renderer?.blockedMessage =
            if (allowed()) null else carContext.getString(dev.autobridge.desktop.R.string.desktop_park_to_browse)
        rail.visible = allowed()
    }

    /**
     * The car area the page uses, 1:1.
     *
     * Full screen as the app: the whole surface. The host's areas reserve a band at the top for
     * its action strip even while that strip is auto-hidden (DHU: stable area (18,132)-(1262,582)
     * on a 1280×600 surface), which showed as black bars; the strip simply floats over the page
     * for the few seconds it is shown, as it does over a map.
     *
     * On the Coolwalk home screen the app is the big card and the host draws widgets over part of
     * it (stable area (504,51)-(1247,564)): then the page goes in the part left uncovered.
     */
    private fun pageArea(): Rect {
        val full = Rect(0, 0, surfaceWidth, surfaceHeight)
        val stable = stableArea ?: return full
        return if (stable.width() < surfaceWidth * 0.8f) stable else full
    }

    private fun fit() {
        val s = session ?: return
        val area = pageArea()
        s.fitTo(area.width(), area.height())
        s.renderer.setArea(area)
    }

    // ── SurfaceCallback ─────────────────────────────────────────────────────

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        val browser = adapter ?: return
        surfaceWidth = surfaceContainer.width
        surfaceHeight = surfaceContainer.height
        surfaceDpi = surfaceContainer.dpi
        rail.density = surfaceDpi / 160f
        DesktopLog.i("car surface ${surfaceWidth}x$surfaceHeight dpi=$surfaceDpi")
        val area = pageArea()
        val s = try {
            DesktopEngine.openSession(browser.id, area.width(), area.height(), DesktopEngine.carScale(surfaceDpi))
        } catch (e: Exception) {
            DesktopLog.w("car session failed", e)
            return
        }
        session = s
        s.renderer.placeholderText = carContext.getString(dev.autobridge.desktop.R.string.desktop_starting)
        s.renderer.overlay = rail
        s.renderer.setSurfaceDrawer { target, block -> CarSurfaceCanvas.draw(carContext, target, block) }
        applyGate()
        s.renderer.attachSurface(surface, surfaceWidth, surfaceHeight)
        fit()
        DesktopEngine.noteSurfaceAttached()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        session?.detachSurface()
        DesktopEngine.noteSurfaceDetached()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        if (stableArea.isEmpty || stableArea.width() < surfaceWidth / 3 || stableArea.height() < surfaceHeight / 2) return
        DesktopLog.i("car stable area $stableArea")
        this.stableArea = Rect(stableArea)
        fit()
    }

    override fun onClick(x: Float, y: Float) {
        if (!allowed()) return
        val s = session ?: return
        when (rail.hit(x, y)) {
            ControlRail.Button.TOUCH -> { rail.mode = PointerMode.TOUCH; s.renderer.burst(); return }
            ControlRail.Button.MOUSE -> { rail.mode = PointerMode.MOUSE; s.renderer.burst(); return }
            ControlRail.Button.KEYBOARD -> { openKeyboard(); return }
            ControlRail.Button.SETTINGS -> { openSettings(); return }
            null -> Unit
        }
        if (rail.mode == PointerMode.MOUSE) {
            s.input?.clickAtCursor()
        } else {
            val (dx, dy) = s.renderer.mapping?.toDisplay(x, y) ?: return
            s.input?.click(dx, dy)
        }
        s.renderer.burst()
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (!allowed()) return
        val s = session ?: return
        val map = s.renderer.mapping ?: return
        if (rail.mode == PointerMode.MOUSE) {
            // Pan moves the cursor with the finger (distance is previous minus current).
            s.input?.moveBy(-map.toDisplayDistance(distanceX), -map.toDisplayDistance(distanceY), map.displayWidth, map.displayHeight)
        } else {
            val (cx, cy) = map.toDisplay(surfaceWidth / 2f, surfaceHeight / 2f)
            s.input?.scroll(cx, cy, map.toDisplayDistance(distanceX), map.toDisplayDistance(distanceY))
        }
        s.renderer.burst()
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        if (!allowed() || rail.mode == PointerMode.MOUSE) return
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

/** Settings from the rail: navigation the page's own toolbar is too small for, and the input mode. */
class DesktopBrowserCarMenuScreen(
    carContext: CarContext,
    private val session: () -> DesktopSession?,
    private val rail: ControlRail,
) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        fun row(title: Int, action: () -> Unit) = Row.Builder()
            .setTitle(carContext.getString(title))
            .setOnClickListener { action(); session()?.renderer?.burst(); screenManager.pop() }
            .build()
        val input = { session()?.input }
        val list = ItemList.Builder()
            .addItem(row(R.string.desktop_viewer_back) { input()?.back() })
            .addItem(row(R.string.desktop_menu_forward) { input()?.forward() })
            .addItem(row(R.string.desktop_viewer_reload) { input()?.reload() })
            .addItem(row(R.string.desktop_menu_new_tab) { input()?.newTab() })
            .addItem(row(R.string.desktop_menu_zoom_in) { input()?.key('='.code, Keysym.CONTROL_L) })
            .addItem(row(R.string.desktop_menu_zoom_out) { input()?.key('-'.code, Keysym.CONTROL_L) })
            .addItem(row(if (rail.mode == PointerMode.TOUCH) R.string.desktop_car_mode_mouse else R.string.desktop_car_mode_touch) {
                rail.mode = if (rail.mode == PointerMode.TOUCH) PointerMode.MOUSE else PointerMode.TOUCH
            })
            .build()
        return ListTemplate.Builder()
            .setSingleList(list)
            .setHeader(Header.Builder().setTitle(carContext.getString(R.string.desktop_car_settings_title)).setStartHeaderAction(Action.BACK).build())
            .build()
    }
}
