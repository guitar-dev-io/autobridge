package dev.autobridge.projection.desktop

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import dev.autobridge.browser.Box
import dev.autobridge.browser.BrowserIcon
import dev.autobridge.browser.BrowserKeyboardMode
import dev.autobridge.browser.CarSurfaceKeyboard
import dev.autobridge.desktop.display.FrameRenderer
import kotlin.math.roundToInt
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
import dev.autobridge.desktop.browser.DesktopBrowsers
import dev.autobridge.desktop.install.EngineChoice
import dev.autobridge.mirror.MirrorSurfaceOwnership
import androidx.car.app.CarToast
import dev.autobridge.safety.ParkingStateStore
import dev.autobridge.safety.SafetyEnforcement

/**
 * The desktop browser's standalone Android Auto entry ("Desktop Browser"). Declared disabled: the
 * engine is opened from inside AutoBridge (the Web card, once a desktop browser is chosen and
 * installed - see dev.autobridge.car.CarExtensions), so AutoBridge is the one Android Auto app the
 * project lists. Kept so the standalone route can still be switched on for testing with
 * `adb shell pm enable dev.autobridge/dev.autobridge.projection.desktop.DesktopBrowserCarAppService`.
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
 *
 * [embedded]: pushed inside the AutoBridge session rather than the root of the standalone service.
 * The action strip then has Home, back to AutoBridge, and the surface callback is shared with
 * AutoBridge's own surface screens through [MirrorSurfaceOwnership], so popping back cannot clear
 * the callback of the screen underneath.
 */
class DesktopBrowserCarScreen(
    carContext: CarContext,
    private val embedded: Boolean = false,
    /** The browser to show; null shows the one chosen for the Web card. */
    browser: DesktopBrowserAdapter? = null
) : Screen(carContext), SurfaceCallback {
    private var session: DesktopSession? = null
    private val adapter: DesktopBrowserAdapter? = DesktopEngine.let { it.init(carContext); browser ?: it.chosenInstalledBrowser() }
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfaceDpi = 160
    private var stableArea: Rect? = null
    private val rail = ControlRail(carContext, 1f)

    /**
     * AutoBridge's own keyboard, drawn on the car surface over the bottom of the page while it is
     * open (null otherwise): the same keyboard the built-in car browser uses, with its Thai and
     * English layouts. The car's own search keyboard stays one menu row away
     * ([DesktopCarKeyboard]).
     */
    @Volatile private var keyboard: CarSurfaceKeyboard? = null
    private val iconCache = HashMap<Int, Drawable>()

    /** The rail, then the keyboard over the page when it is open. */
    private val overlay = FrameRenderer.Overlay { canvas, width, height ->
        rail.draw(canvas, width, height)
        keyboard?.draw(canvas, keyboardArea(), rail.density, SystemClock.uptimeMillis(), ::drawIcon)
    }

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
                if (adapter != null) {
                    MirrorSurfaceOwnership.claim(this@DesktopBrowserCarScreen)
                    carContext.getCarService(AppManager::class.java).setSurfaceCallback(this@DesktopBrowserCarScreen)
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                ParkingStateStore.removeListener(parkingListener)
                session?.detachSurface()
                DesktopEngine.noteSurfaceDetached()
                if (MirrorSurfaceOwnership.release(this@DesktopBrowserCarScreen)) {
                    carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
                }
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
        // Inside AutoBridge, Home goes back to it: a head unit without a hardware back key has no
        // other way out of a full-screen navigation template.
        val strip = ActionStrip.Builder()
            .addAction(action(R.drawable.ic_browser_back) { session?.input?.back(); session?.renderer?.burst() })
        if (embedded) strip.addAction(action(R.drawable.ic_car_home) { screenManager.popToRoot() })
        return NavigationTemplate.Builder()
            .setActionStrip(strip.build())
            .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
            .build()
    }

    private fun message(title: String, text: String): Template = PaneTemplate.Builder(
        Pane.Builder().addRow(Row.Builder().setTitle(title).addText(text).build()).build()
    ).setHeader(
        Header.Builder().setTitle(title).setStartHeaderAction(if (embedded) Action.BACK else Action.APP_ICON).build()
    ).build()

    private fun action(icon: Int, onClick: () -> Unit): Action =
        Action.Builder().setIcon(CarIcons.of(carContext, icon)).setOnClickListener(onClick).build()

    private fun openKeyboard() {
        if (!DesktopCarKeyboard.useCarKeyboard(carContext)) {
            keyboard = CarSurfaceKeyboard(
                carContext, CarSurfaceKeyboard.Target.FIELD,
                seed = "",
                mode = BrowserKeyboardMode.SEARCH,
                hint = carContext.getString(R.string.desktop_car_keyboard_hint),
            )
            session?.renderer?.burst()
            return
        }
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

    /** The page area the keyboard tray sits at the bottom of, in surface pixels. */
    private fun keyboardArea(): Box {
        val area = pageArea()
        return Box(area.left.toFloat(), area.top.toFloat(), area.right.toFloat(), area.bottom.toFloat())
    }

    private fun drawIcon(canvas: Canvas, icon: BrowserIcon, cx: Float, cy: Float, size: Float, color: Int) {
        val drawable = iconCache.getOrPut(icon.resId) {
            ContextCompat.getDrawable(carContext, icon.resId)?.mutate() ?: return
        }
        val half = size / 2f
        drawable.setBounds((cx - half).roundToInt(), (cy - half).roundToInt(), (cx + half).roundToInt(), (cy + half).roundToInt())
        drawable.setTint(color)
        drawable.draw(canvas)
    }

    /**
     * A tap while AutoBridge's keyboard is open belongs to it. Search types the text into the
     * focused field and presses Enter, as the car keyboard does; a tap above the tray closes it.
     */
    private fun onKeyboardTap(board: CarSurfaceKeyboard, s: DesktopSession, x: Float, y: Float) {
        when (val result = board.onTap(x, y, keyboardArea(), rail.density, SystemClock.uptimeMillis())) {
            is CarSurfaceKeyboard.Result.Commit -> {
                keyboard = null
                if (result.text.isNotEmpty()) s.input?.typeText(result.text)
                s.input?.key(Keysym.RETURN)
            }
            CarSurfaceKeyboard.Result.Close -> keyboard = null
            else -> Unit
        }
        s.renderer.burst()
    }

    private fun applyGate() {
        // Typing is part of browsing: nothing is left open over the page while moving.
        if (!allowed()) keyboard = null
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
        // A surface handed to a screen that no longer owns the callback belongs to its successor.
        if (!MirrorSurfaceOwnership.isOwner(this)) return
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
        s.renderer.overlay = overlay
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
        keyboard?.let { board ->
            onKeyboardTap(board, s, x, y)
            return
        }
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
            .addItem(
                row(
                    if (DesktopCarKeyboard.useCarKeyboard(carContext)) R.string.desktop_car_keyboard_use_autobridge
                    else R.string.desktop_car_keyboard_use_car
                ) { DesktopCarKeyboard.setUseCarKeyboard(carContext, !DesktopCarKeyboard.useCarKeyboard(carContext)) }
            )
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

/**
 * Which browser the car's Web card opens: AutoBridge's built-in browser or an installed desktop
 * browser. The same choice as Settings › Desktop Browser Engine on the phone (one [EngineChoice]),
 * offered on the car so switching back to the built-in browser never needs the phone. A browser that
 * is not installed is listed with what it needs instead of being selectable.
 */
class DesktopEngineChoiceCarScreen(
    carContext: CarContext,
    /** From the Desktop browser home card: picking a browser opens it rather than going back. */
    private val openAfterChoice: Boolean = false
) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        DesktopEngine.init(carContext)
        val chosen = DesktopEngine.chosenInstalledBrowser()?.id
        fun mark(title: String, selected: Boolean) = if (selected) "$title ✓" else title
        val list = ItemList.Builder().addItem(
            Row.Builder()
                .setTitle(mark(carContext.getString(R.string.desktop_settings_choice_autobridge), chosen == null))
                .addText(carContext.getString(R.string.desktop_settings_choice_autobridge_caption))
                .setOnClickListener { choose(EngineChoice.AUTOBRIDGE) }
                .build()
        )
        DesktopBrowsers.all.forEach { browser ->
            val installed = DesktopEngine.state(browser.id).state.installed
            val choice = EngineChoice.entries.first { it.browser == browser.id }
            list.addItem(
                Row.Builder()
                    .setTitle(mark(browser.displayName, chosen == browser.id))
                    .addText(
                        carContext.getString(
                            if (installed) R.string.desktop_settings_choice_installed else R.string.car_engine_install_on_phone
                        )
                    )
                    .setOnClickListener {
                        if (installed) {
                            choose(choice)
                        } else {
                            CarToast.makeText(
                                carContext, carContext.getString(R.string.car_engine_install_on_phone), CarToast.LENGTH_LONG
                            ).show()
                        }
                    }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setSingleList(list.build())
            .setHeader(
                Header.Builder().setTitle(carContext.getString(R.string.car_engine_title))
                    .setStartHeaderAction(Action.BACK).build()
            )
            .build()
    }

    private fun choose(choice: EngineChoice) {
        DesktopEngine.choice = choice
        val browser = choice.browser?.let(DesktopBrowsers::of)
        screenManager.pop()
        if (openAfterChoice && browser != null) {
            dev.autobridge.car.CarNavigation.open(screenManager, "DesktopBrowserCarScreen") {
                DesktopBrowserCarScreen(carContext, embedded = true, browser = browser)
            }
        }
    }
}

/**
 * Which keyboard the rail's keyboard button opens on the car: AutoBridge's own (the default, the
 * same one the built-in browser draws) or the car's search keyboard. Switched from the rail's
 * settings list and remembered.
 */
object DesktopCarKeyboard {
    private const val PREFS = "desktop_car"
    private const val KEY_USE_CAR_KEYBOARD = "use_car_keyboard"

    fun useCarKeyboard(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_USE_CAR_KEYBOARD, false)

    fun setUseCarKeyboard(context: Context, use: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_USE_CAR_KEYBOARD, use) }
}
