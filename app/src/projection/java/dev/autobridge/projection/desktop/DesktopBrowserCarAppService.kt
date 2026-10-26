package dev.autobridge.projection.desktop

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import dev.autobridge.browser.Box
import dev.autobridge.browser.BrowserIcon
import dev.autobridge.browser.BrowserKeyboardMode
import dev.autobridge.browser.CarKeyboardGestures
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import dev.autobridge.BuildConfig
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
import dev.autobridge.desktop.keyboard.Shortcut
import dev.autobridge.desktop.keyboard.TypingTarget
import dev.autobridge.display.CarSurfaceCanvas
import dev.autobridge.desktop.session.DesktopSession
import dev.autobridge.desktop.browser.DesktopBrowsers
import dev.autobridge.desktop.install.EngineChoice
import dev.autobridge.mirror.MirrorSurfaceOwnership
import androidx.car.app.CarToast
import dev.autobridge.safety.BypassPolicyStore
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
    browser: DesktopBrowserAdapter? = null,
    /** A page to open on arrival (a streaming site): a new tab when the browser is already running. */
    startUrl: String? = null
) : Screen(carContext), SurfaceCallback {
    /** Used once: the surface can come back (and the session be reopened) with no new request. */
    private var pendingUrl: String? = startUrl
    private var session: DesktopSession? = null
    private var surface: android.view.Surface? = null
    private val adapter: DesktopBrowserAdapter? = DesktopEngine.let { it.init(carContext); browser ?: it.chosenInstalledBrowser() }
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfaceDpi = 160
    private var stableArea: Rect? = null
    private val rail = ControlRail(carContext, 1f, modeKey = "pointer_mode_car").apply {
        // The car's Appearance; the phone's desktop browser keeps the fixed rail.
        followCarLook = true
        showKeyboardChoice = true
        keyboardChoice = DesktopCarKeyboard.choice(carContext).ordinal
    }

    /**
     * AutoBridge's own keyboard, drawn on the car surface over the bottom of the page while it is
     * open (null otherwise): the same keyboard the built-in car browser uses, with its Thai and
     * English layouts. The car's own search keyboard stays one menu row away
     * ([DesktopCarKeyboard]).
     */
    @Volatile private var keyboard: CarSurfaceKeyboard? = null

    /** What the open keyboard types into: the focused page field, or the address bar (after Ctrl+L). */
    private var keyboardTarget = TypingTarget.FIELD

    /** The open keyboard has typed something: hiding it on the address bar then sends Escape (Chrome restores the URL). */
    private var keyboardEdited = false

    /** The car's search keyboard while it is on top, typing live; popped with rollback if typing stops being allowed. */
    private var searchKeyboard: CarBrowserSearchScreen? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Fast double taps on the drawn keyboard arrive as one onScale; this turns them back into taps. */
    private val doubleTap = KeyboardDoubleTap(
        clock = SystemClock::uptimeMillis,
        schedule = { delay, block ->
            val task = Runnable(block)
            mainHandler.postDelayed(task, delay)
            ({ mainHandler.removeCallbacks(task) })
        },
        density = { rail.density },
        onTray = { x, y ->
            keyboard?.let { CarKeyboardGestures.inKeyboardArea(x, y, it.geometry(keyboardArea(), rail.density).tray) } == true
        },
        replay = { x, y ->
            val board = keyboard
            val s = session
            if (board != null && s != null && allowed()) onKeyboardTap(board, s, x, y)
        },
    )
    /** With no keyboard open, a host double-tap on the page clicks instead of zooming; a real pinch still zooms. */
    private val pageDoubleTap = PageDoubleTap(
        clock = SystemClock::uptimeMillis,
        schedule = { delay, block ->
            val task = Runnable(block)
            mainHandler.postDelayed(task, delay)
            ({ mainHandler.removeCallbacks(task) })
        },
        doubleClick = { x, y ->
            val s = session
            val (dx, dy) = s?.renderer?.mapping?.toDisplay(x, y) ?: return@PageDoubleTap
            s.input?.click(dx, dy)
            s.input?.click(dx, dy)
            s.renderer.burst()
        },
        zoom = { x, y, factor ->
            val s = session
            val (dx, dy) = s?.renderer?.mapping?.toDisplay(x, y) ?: return@PageDoubleTap
            s.input?.zoom(dx, dy, factor)
            s.renderer.burst()
        },
    )
    private val iconCache = HashMap<Int, Drawable>()

    /** The rail, then the keyboard over the page when it is open. */
    private val overlay = FrameRenderer.Overlay { canvas, width, height ->
        rail.draw(canvas, width, height)
        keyboard?.draw(canvas, keyboardArea(), rail.density, SystemClock.uptimeMillis(), drawIcon = ::drawIcon)
    }

    /**
     * Browsing is allowed while parked, or while moving when "Allow while the car is moving" is on
     * (Settings › Desktop Browser Engine, default on). Off, only a car that reports parked qualifies.
     */
    private fun allowed(): Boolean =
        SafetyEnforcement.gateParked(ParkingStateStore.isParked) &&
            (DesktopEngine.store.allowWhileMoving || ParkingStateStore.isParked || BypassPolicyStore.overridesParked) &&
            FeaturePolicy.app.isAvailable(Feature.BROWSER)

    private val parkingListener: (ParkingStateStore.State) -> Unit = {
        applyGate()
        invalidate()
    }

    /** The bypass lifts the typing gate's parked requirement, so turning it off can close the keyboard. */
    private val bypassListener: (BypassPolicyStore.State) -> Unit = {
        applyGate()
        invalidate()
    }

    init {
        KeyboardBridgeDebug.register(carContext)
        // A zoom change in Settings restarts the browser; carry on with the new session.
        lifecycleScope.launch {
            DesktopEngine.session.collect { s ->
                if (s != null && s !== session && session != null && s.adapter.id == adapter?.id && surface != null) bind(s)
            }
        }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                ParkingStateStore.addListener(parkingListener)
                BypassPolicyStore.addListener(bypassListener)
                if (adapter != null) {
                    MirrorSurfaceOwnership.claim(this@DesktopBrowserCarScreen)
                    carContext.getCarService(AppManager::class.java).setSurfaceCallback(this@DesktopBrowserCarScreen)
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                ParkingStateStore.removeListener(parkingListener)
                BypassPolicyStore.removeListener(bypassListener)
                doubleTap.cancel()
                pageDoubleTap.cancel()
                if (rail.dragHeld) session?.input?.releaseAtCursor()
                session?.detachSurface()
                dropSurface()
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

    /**
     * The chosen keyboard, parked only ([KeyboardTypingGate]): AutoBridge's, drawn on the page; the
     * car's search box; or the phone's own keyboard through a notification. The first two type
     * into the page live, key by key; [TypingTarget.ADDRESS] types into the browser's address bar
     * (the bridge sends Ctrl+L first, so the text replaces the URL).
     */
    private fun openKeyboard(target: TypingTarget) {
        if (!allowed()) return
        if (!KeyboardTypingGate.allowed()) {
            toast(KeyboardTypingGate.refusalMessage())
            return
        }
        val address = target == TypingTarget.ADDRESS
        when (DesktopCarKeyboard.choice(carContext)) {
            DesktopCarKeyboard.Choice.AUTOBRIDGE -> {
                val keys = session?.input?.keyboard ?: return
                keys.prepareForTyping(target)
                keyboardTarget = target
                keyboardEdited = false
                keyboard = CarSurfaceKeyboard(
                    carContext,
                    if (address) CarSurfaceKeyboard.Target.ADDRESS else CarSurfaceKeyboard.Target.FIELD,
                    seed = "",
                    mode = if (address) BrowserKeyboardMode.URL else BrowserKeyboardMode.SEARCH,
                    hint = carContext.getString(if (address) R.string.car_keyboard_hint else R.string.desktop_car_keyboard_hint),
                    live = true,
                )
                session?.renderer?.burst()
            }
            DesktopCarKeyboard.Choice.CAR -> {
                val keys = session?.input?.keyboard ?: return
                keys.prepareForTyping(target)
                val screen = CarBrowserSearchScreen(
                    carContext,
                    "",
                    field = if (address) null else CarBrowserSearchScreen.Field("", "text"),
                    onTextChanged = { text ->
                        // The whole text so far: the bridge types only the difference.
                        if (KeyboardTypingGate.allowed()) {
                            session?.input?.keyboard?.setComposingText(text)
                            session?.renderer?.burst()
                        }
                    },
                )
                searchKeyboard = screen
                screenManager.pushForResult(screen) { result ->
                    searchKeyboard = null
                    val bridge = session?.input?.keyboard ?: return@pushForResult
                    if (result != null && KeyboardTypingGate.allowed()) {
                        // Already typed live: only its non-null-ness matters (it is trimmed).
                        bridge.finishComposing()
                        bridge.key(Keysym.RETURN)
                    } else {
                        // Back (or typing stopped being allowed): take the live text out again.
                        bridge.setComposingText("")
                        if (address) bridge.key(Keysym.ESCAPE)
                    }
                    session?.renderer?.burst()
                }
            }
            // The phone companion focuses Chrome itself (once per notification tap).
            DesktopCarKeyboard.Choice.PHONE -> toast(
                if (DesktopPhoneKeyboard.request(carContext, target)) {
                    R.string.desktop_keyboard_phone_open_notification
                } else {
                    R.string.desktop_keyboard_phone_notifications_off
                }
            )
        }
    }

    private fun toast(message: Int) =
        CarToast.makeText(carContext, carContext.getString(message), CarToast.LENGTH_LONG).show()

    /**
     * Hides AutoBridge's keyboard. Nothing is typed by hiding it; on the address bar, edits that
     * were not sent with Go are undone by Escape (Chrome puts the URL back).
     */
    private fun closeKeyboard(submitted: Boolean = false) {
        if (keyboard == null) return
        keyboard = null
        doubleTap.cancel()
        val keys = session?.input?.keyboard ?: return
        keys.finishComposing()
        if (!submitted && keyboardTarget == TypingTarget.ADDRESS && keyboardEdited) keys.key(Keysym.ESCAPE)
    }

    /** Debug builds: what the host delivers while the keyboard is open, to tune [KeyboardDoubleTap]. */
    private fun logGesture(kind: String, x: Float, y: Float, f: Float) {
        if (BuildConfig.DEBUG) Log.d(KB_TEST_TAG, "gesture kind=$kind x=$x y=$y f=$f t=${SystemClock.uptimeMillis()}")
    }

    /**
     * Gestures while AutoBridge's keyboard is open never reach the page: pans and flings are
     * dropped, and a scale is a double tap on a key or nothing (no page zoom while typing).
     */
    private fun keyboardGesture(kind: String, x: Float, y: Float, f: Float) {
        logGesture(kind, x, y, f)
        if (kind == GESTURE_SCALE && allowed()) doubleTap.onScale(x, y, f)
    }

    /** What the rail asked for; the rail has already changed its own state. */
    private fun onRailAction(action: ControlRail.Action) {
        val input = session?.input
        when (action) {
            ControlRail.Action.BACK -> input?.back()
            ControlRail.Action.FORWARD -> input?.forward()
            ControlRail.Action.RELOAD -> input?.reload()
            ControlRail.Action.NEW_TAB -> input?.newTab()
            ControlRail.Action.ZOOM_IN -> input?.key('='.code, Keysym.CONTROL_L)
            ControlRail.Action.ZOOM_OUT -> input?.key('-'.code, Keysym.CONTROL_L)
            ControlRail.Action.RESET_ZOOM -> input?.key('0'.code, Keysym.CONTROL_L)
            ControlRail.Action.ADDRESS -> openKeyboard(TypingTarget.ADDRESS)
            ControlRail.Action.KEYBOARD -> openKeyboard(TypingTarget.FIELD)
            ControlRail.Action.KEYBOARD_AUTOBRIDGE -> DesktopCarKeyboard.setChoice(carContext, DesktopCarKeyboard.Choice.AUTOBRIDGE)
            ControlRail.Action.KEYBOARD_CAR -> DesktopCarKeyboard.setChoice(carContext, DesktopCarKeyboard.Choice.CAR)
            ControlRail.Action.KEYBOARD_PHONE -> DesktopCarKeyboard.setChoice(carContext, DesktopCarKeyboard.Choice.PHONE)
            // The Text row types into the page: parked-only like every keyboard on the car.
            // Paste here is the guest's own clipboard (what was copied in Chrome).
            ControlRail.Action.TAB,
            ControlRail.Action.ESCAPE,
            ControlRail.Action.SELECT_ALL,
            ControlRail.Action.COPY,
            ControlRail.Action.PASTE -> {
                if (!KeyboardTypingGate.allowed()) {
                    toast(KeyboardTypingGate.refusalMessage())
                    return
                }
                val keys = input?.keyboard ?: return
                when (action) {
                    ControlRail.Action.TAB -> keys.key(Keysym.TAB)
                    ControlRail.Action.ESCAPE -> keys.key(Keysym.ESCAPE)
                    ControlRail.Action.SELECT_ALL -> keys.shortcut(Shortcut.SELECT_ALL)
                    ControlRail.Action.COPY -> keys.shortcut(Shortcut.COPY)
                    else -> keys.shortcut(Shortcut.PASTE)
                }
            }
            ControlRail.Action.DRAG_START -> input?.pressAtCursor()
            ControlRail.Action.DRAG_END -> input?.releaseAtCursor()
            ControlRail.Action.MODE_CHANGED -> {
                // Leaving mouse mode never leaves the button held down.
                if (rail.dragHeld) { input?.releaseAtCursor(); rail.dragHeld = false }
                // Entering it, the pointer starts in the middle of the page, where it is easy to find.
                if (rail.mode == PointerMode.MOUSE) {
                    session?.renderer?.mapping?.let { m -> input?.moveTo(m.displayWidth / 2, m.displayHeight / 2) }
                }
                // Touch mode shows no on-page cursor; mouse/drag show the X arrow.
                input?.let { if (rail.mode == PointerMode.TOUCH) it.hideCursor() else it.showCursor() }
            }
            // The car leaves with Home or Back; there is no Close on the car.
            ControlRail.Action.CLOSE -> Unit
        }
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
     * A tap while AutoBridge's keyboard is open belongs to it. Each key goes to the page as it is
     * tapped (live); Search presses Enter (the text is already there); a tap above the tray hides
     * the keyboard. Parked only: a tap once typing is no longer allowed just closes it.
     */
    private fun onKeyboardTap(board: CarSurfaceKeyboard, s: DesktopSession, x: Float, y: Float) {
        if (!KeyboardTypingGate.allowed()) {
            closeKeyboard()
            s.renderer.burst()
            return
        }
        val result = board.onTap(x, y, keyboardArea(), rail.density, SystemClock.uptimeMillis())
        val keys = s.input?.keyboard
        val edit = board.lastEdit
        if (edit != null) keyboardEdited = true
        when (edit) {
            is CarSurfaceKeyboard.Edit.Inserted -> keys?.commitText(edit.text)
            CarSurfaceKeyboard.Edit.DeletedBack -> keys?.key(Keysym.BACKSPACE)
            is CarSurfaceKeyboard.Edit.Cursor -> keys?.key(if (edit.dir < 0) Keysym.LEFT else Keysym.RIGHT)
            CarSurfaceKeyboard.Edit.Cleared -> {
                keys?.key('a'.code, Keysym.CONTROL_L)
                keys?.key(Keysym.BACKSPACE)
            }
            null -> Unit
        }
        when (result) {
            is CarSurfaceKeyboard.Result.Commit -> {
                keys?.key(Keysym.RETURN)
                closeKeyboard(submitted = true)
            }
            CarSurfaceKeyboard.Result.Close -> closeKeyboard()
            else -> Unit
        }
        s.renderer.burst()
    }

    private fun applyGate() {
        // Typing is parked-only even while the browsing gate only reports: close every keyboard.
        if (!KeyboardTypingGate.allowed()) {
            closeKeyboard()
            searchKeyboard?.let { screen ->
                // Popping it reports no result, which takes its live text out of the page.
                mainHandler.post { if (searchKeyboard === screen) screenManager.remove(screen) }
            }
        }
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
        this.surface = surface
        val area = pageArea()
        val scale = DesktopEngine.carScale(surfaceDpi, area.width())
        // Remembered so "start on launch" opens the browser at this car's size.
        DesktopEngine.store.setLastCar(area.width(), area.height(), scale)
        val s = try {
            DesktopEngine.openSession(browser.id, area.width(), area.height(), scale, pendingUrl.also { pendingUrl = null })
        } catch (e: Exception) {
            DesktopLog.w("car session failed", e)
            return
        }
        bind(s)
    }

    /** Shows [s] on this screen's surface with this screen's rail and drawing guard. */
    private fun bind(s: DesktopSession) {
        session = s
        s.renderer.placeholderText = carContext.getString(dev.autobridge.desktop.R.string.desktop_starting)
        rail.area = { s.renderer.mapping?.let { m -> android.graphics.RectF(m.offsetX, m.offsetY, m.offsetX + m.drawWidth, m.offsetY + m.drawHeight) } }
        s.renderer.overlay = overlay
        rail.onAutoHide = { session?.renderer?.requestFrame() }
        rail.dragHeld = false
        s.renderer.setSurfaceDrawer { target, block -> CarSurfaceCanvas.draw(carContext, target, block) }
        applyGate()
        surface?.let { s.renderer.attachSurface(it, surfaceWidth, surfaceHeight) }
        fit()
        // The rail starts in TOUCH, so hide the X cursor at session start to match the default.
        if (rail.mode == PointerMode.TOUCH) s.input?.hideCursor() else s.input?.showCursor()
        DesktopEngine.noteSurfaceAttached()
    }

    /**
     * Lets go of this screen's producer connection to the car surface. Drawing on it connected the
     * surface to us, and the screen that takes over (the car home after Home) cannot lock a canvas
     * on a surface that is still connected: its frames failed, so the car kept showing Chrome's
     * last frame and looked hung. The host still owns the surface itself.
     */
    private fun dropSurface() {
        surface?.let { runCatching { it.release() } }
        surface = null
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        session?.detachSurface()
        dropSurface()
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
            logGesture("click", x, y, 0f)
            doubleTap.onClick(x, y)
            onKeyboardTap(board, s, x, y)
            return
        }
        // The rail's handle, panel and More sheet take their taps first; the rest is the page.
        when (val tap = rail.onTap(x, y)) {
            is ControlRail.Tap.Perform -> {
                onRailAction(tap.action)
                s.renderer.burst()
                return
            }
            ControlRail.Tap.Consumed -> {
                s.renderer.burst()
                return
            }
            ControlRail.Tap.PassThrough -> Unit
        }
        if (rail.mode == PointerMode.MOUSE) {
            // A tap ends a drag (drops what was dragged); otherwise it clicks at the pointer.
            if (rail.dragHeld) {
                s.input?.releaseAtCursor()
                rail.dragHeld = false
            } else {
                s.input?.clickAtCursor()
            }
        } else {
            val (dx, dy) = s.renderer.mapping?.toDisplay(x, y) ?: return
            s.input?.click(dx, dy)
        }
        s.renderer.burst()
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (keyboard != null) return keyboardGesture("scroll", distanceX, distanceY, 0f)
        if (!allowed() || rail.consumesScroll()) return
        val s = session ?: return
        val map = s.renderer.mapping ?: return
        if (rail.mode == PointerMode.MOUSE && rail.dragHeld) {
            // Drag armed: move the pointer with the left button already down (pressed by
            // DRAG_START), so a one-finger drag selects text / drags elements.
            s.input?.moveBy(-map.toDisplayDistance(distanceX), -map.toDisplayDistance(distanceY), map.displayWidth, map.displayHeight)
        } else {
            // Touch and plain mouse both wheel-scroll the page: a head unit cannot do a two-finger
            // scroll, so a one-finger drag scrolls. Mouse still places + clicks the cursor on a tap.
            val (cx, cy) = map.toDisplay(surfaceWidth / 2f, surfaceHeight / 2f)
            s.input?.scroll(cx, cy, map.toDisplayDistance(distanceX), map.toDisplayDistance(distanceY))
        }
        s.renderer.burst()
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        if (keyboard != null) return keyboardGesture("fling", velocityX, velocityY, 0f)
        // Mouse/touch both momentum-scroll; only a drag-held pan (click-drag) must not fling.
        if (!allowed() || rail.consumesScroll() || (rail.mode == PointerMode.MOUSE && rail.dragHeld)) return
        val s = session ?: return
        val map = s.renderer.mapping ?: return
        val (cx, cy) = map.toDisplay(surfaceWidth / 2f, surfaceHeight / 2f)
        s.input?.scroll(cx, cy, map.toDisplayDistance(-velocityX * 0.2f), map.toDisplayDistance(-velocityY * 0.2f))
        s.renderer.burst()
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (keyboard != null) return keyboardGesture(GESTURE_SCALE, focusX, focusY, scaleFactor)
        if (!allowed()) return
        // No keyboard: a host double-tap (one isolated big-factor onScale) double-clicks at the
        // focus point, mapped the same way onClick maps a TOUCH tap; a real pinch still zooms.
        pageDoubleTap.onScale(focusX, focusY, scaleFactor)
    }

    private companion object {
        /** The debug harness's logcat tag ([KeyboardBridgeDebug]). */
        const val KB_TEST_TAG = "AutoBridgeKbTest"
        const val GESTURE_SCALE = "scale"
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
 * Which keyboard the rail's keyboard button opens on the car: AutoBridge's own (drawn on the
 * page, the same one the built-in browser draws), the car's search keyboard, or the phone's own
 * keyboard (a notification opens it on the phone). Switched from the rail's settings list and
 * remembered. [Choice.ordinal] is the rail's `keyboardChoice`.
 */
object DesktopCarKeyboard {
    enum class Choice { AUTOBRIDGE, CAR, PHONE }

    private const val PREFS = "desktop_car"
    private const val KEY_CHOICE = "keyboard_choice"
    /** Before the three-way choice: true = the car's keyboard. */
    private const val KEY_USE_CAR_KEYBOARD = "use_car_keyboard"

    /**
     * The stored choice, else the old two-way setting (true → CAR, false → AUTOBRIDGE), else CAR:
     * the host's own keyboard is the supported Android Auto input path.
     */
    fun resolve(stored: String?, legacy: Boolean?): Choice =
        Choice.entries.firstOrNull { it.name == stored } ?: when (legacy) {
            true -> Choice.CAR
            false -> Choice.AUTOBRIDGE
            null -> Choice.CAR
        }

    fun choice(context: Context): Choice {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val legacy = if (prefs.contains(KEY_USE_CAR_KEYBOARD)) prefs.getBoolean(KEY_USE_CAR_KEYBOARD, false) else null
        return resolve(prefs.getString(KEY_CHOICE, null), legacy)
    }

    fun setChoice(context: Context, choice: Choice) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_CHOICE, choice.name) }
}
