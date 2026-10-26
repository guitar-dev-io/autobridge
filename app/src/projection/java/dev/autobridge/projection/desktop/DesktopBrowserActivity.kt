package dev.autobridge.projection.desktop

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import dev.autobridge.R
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.desktop.DesktopEngine
import dev.autobridge.desktop.browser.DesktopBrowserId
import dev.autobridge.desktop.browser.DesktopBrowsers
import dev.autobridge.desktop.display.DesktopView
import dev.autobridge.desktop.input.ControlRail
import dev.autobridge.desktop.input.PointerMode
import dev.autobridge.desktop.input.Keysym
import dev.autobridge.desktop.input.TouchGestureMapper
import dev.autobridge.desktop.keyboard.ClipboardSync
import dev.autobridge.desktop.keyboard.Shortcut
import dev.autobridge.desktop.keyboard.TypingTarget
import dev.autobridge.desktop.session.DesktopSession
import dev.autobridge.i18n.AppLocale
import dev.autobridge.safety.BypassPolicyStore
import dev.autobridge.safety.ParkingStateStore
import dev.autobridge.safety.SafetyEnforcement
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.dp
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The desktop browser on the phone, full screen in either orientation.
 *
 * The browser window is resized to the screen's own shape (DesktopEngine.phoneViewport), so the
 * page fills the screen in portrait and in landscape, and rotating the phone reflows the page
 * instead of letterboxing it. The same [ControlRail] as on the car sits on the left edge as a
 * handle; tapped, it opens back, address, type, touch ⇄ mouse and More (forward, reload, new tab,
 * zoom, how soon it collapses, and close).
 *
 * Started as "AutoBridge Chrome" ([DesktopCarEntry], a CAR_LAUNCHER alias) it is a window on the car
 * display: the phone's keyboard comes up there, the car's orientation is left alone, and it is
 * parked-only like the template car screen. The page is sized to the window the car gives it.
 * Typing there is parked-only too ([KeyboardTypingGate]). Started as the DesktopBrowserPane alias
 * (a Multi-app pane) it is not the car entry: ungated, like the phone viewer.
 *
 * The phone's keyboard ([ChromeKeyInputView]) comes up from the rail's Type and Address, and after
 * a tap on the page when Chrome shows the text cursor there (a field was tapped).
 *
 * Leaving the screen only detaches the view; the browser keeps its tabs and is stopped after
 * [DesktopEngine.IDLE_STOP_MS] if nobody comes back.
 */
class DesktopBrowserActivity : Activity() {
    private val scope = MainScope()
    private var session: DesktopSession? = null
    private lateinit var browserId: DesktopBrowserId
    private lateinit var desktopView: DesktopView
    private lateinit var keyInput: ChromeKeyInputView
    private lateinit var statusText: TextView
    private lateinit var gestures: TouchGestureMapper
    private lateinit var rail: ControlRail
    private var imeHeight = 0
    /** Started through the CAR_LAUNCHER alias: on the car display, parked-only. */
    private var carEntry = false
    private val parkingListener: (ParkingStateStore.State) -> Unit = {
        if (::desktopView.isInitialized) desktopView.post { applyGate() }
    }
    private val bypassListener: (BypassPolicyStore.State) -> Unit = {
        if (::desktopView.isInitialized) desktopView.post { applyGate() }
    }
    /** Android's clipboard follows into the guest while this is on screen. */
    private var clipWatch: AutoCloseable? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.rebase(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DesktopEngine.init(this)
        carEntry = DesktopCarEntry.isCarEntry(intent?.component?.className)
        // The car display has one fixed orientation; only the phone viewer rotates.
        if (!carEntry) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        // Started by name (the Multi-app pane) there is no extra: the browser chosen for the Web
        // card, else the first one installed, since a pane asks for "the desktop browser".
        val id = DesktopBrowserId.fromKey(intent.getStringExtra(EXTRA_BROWSER))
            ?: DesktopEngine.chosenInstalledBrowser()?.id
            ?: DesktopBrowsers.all.firstOrNull { DesktopEngine.state(it.id).state.installed }?.id
        if (id == null || !DesktopEngine.state(id).state.installed) {
            Toast.makeText(this, R.string.desktop_settings_install_first, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        browserId = id
        rail = ControlRail(this, resources.displayMetrics.density).apply { showClose = true }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        desktopView = DesktopView(this)
        root.addView(desktopView, FrameLayout.LayoutParams(-1, -1))
        keyInput = ChromeKeyInputView(this, { session }, gated = carEntry)
        root.addView(keyInput, FrameLayout.LayoutParams(1, 1))
        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setBackgroundColor(AutoBridgeDesign.tint(Color.BLACK, 0.7f))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
        }
        root.addView(statusText, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(16) })
        setContentView(root)
        hideSystemBars()

        gestures = TouchGestureMapper(this, { session?.input }, { session?.renderer?.mapping }, { rail.mode }) { session?.renderer?.burst() }
        ParkingStateStore.addListener(parkingListener)
        BypassPolicyStore.addListener(bypassListener)
        KeyboardBridgeDebug.register(this)
        desktopView.setOnTouchListener { _, event -> onPageTouch(event) }
        desktopView.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) fit()
        }
        // The keyboard takes the bottom of the screen: the browser window shrinks to what is left
        // above it, so the field being typed in stays visible. A car window may never report the
        // ime inset; then imeHeight stays 0, the page keeps the whole window and the keyboard
        // simply overlays its bottom (adjustNothing), which fit() already handles.
        // A zoom change in Settings restarts the browser; follow it to the new session.
        scope.launch {
            DesktopEngine.session.collect { s ->
                // Only while on screen: a viewer left in the background must not pull a session
                // the car just opened onto the phone (it took the car's frames on the DHU).
                if (resumed && s != null && s !== session && session != null && s.adapter.id == browserId) {
                    attach(s)
                    fit()
                }
            }
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
            if (ime != imeHeight) {
                // Hidden (Back, the keyboard's own key): composing ends, the IME starts over.
                if (ime == 0 && imeHeight > 0) {
                    keyInput.onImeHidden()
                    refocusAddressAfterResize = false
                }
                imeHeight = ime
                fit()
                if (ime > 0 && refocusAddressAfterResize) {
                    refocusAddressAfterResize = false
                    desktopView.postDelayed({ session?.input?.focusAddressBar() }, 350)
                }
            }
            insets
        }
    }

    /** Opens or re-fits the session for the view's current size and keyboard. */
    private fun fit() {
        // On the car entry a layout change while backgrounded must not take the renderer back
        // from the template car screen; onResume fits again.
        if (carEntry && !resumed) return
        val w = desktopView.width
        val h = desktopView.height - imeHeight
        if (w <= 0 || h <= 0) return
        val (vw, vh) = DesktopEngine.phoneViewport(w, desktopView.height).let { (fw, fh) ->
            fw to (fh * h / desktopView.height)
        }
        val s = session ?: try {
            DesktopEngine.openSession(browserId, vw, vh, 1f, intent.getStringExtra(EXTRA_URL).also { intent.removeExtra(EXTRA_URL) }).also { attach(it) }
        } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        s.fitTo(vw, vh)
        s.renderer.setArea(android.graphics.Rect(0, 0, w, h))
        s.renderer.attachView(desktopView, desktopView.width, desktopView.height)
        DesktopEngine.noteSurfaceAttached()
        applyGate()
    }

    private fun allowed(): Boolean = !carEntry ||
        DesktopCarEntry.allowed(
            SafetyEnforcement.gateParked(ParkingStateStore.isParked) &&
                (DesktopEngine.store.allowWhileMoving || ParkingStateStore.isParked || BypassPolicyStore.overridesParked),
            FeaturePolicy.app.isAvailable(Feature.BROWSER)
        )

    /** Car entry only: a notice instead of the page, no rail and no keyboard unless parked. */
    private fun applyGate() {
        if (!carEntry || !::keyInput.isInitialized) return
        val ok = allowed()
        rail.visible = ok
        session?.renderer?.blockedMessage = if (ok) null else when {
            !SafetyEnforcement.gateParked(ParkingStateStore.isParked) -> getString(dev.autobridge.desktop.R.string.desktop_park_to_browse)
            else -> FeaturePolicy.app.denialMessage(Feature.BROWSER)
        }
        if (!ok) {
            keyInput.hide()
            if (rail.dragHeld) { session?.input?.releaseAtCursor(); rail.dragHeld = false }
        } else if (!keyInput.typingAllowed() && keyInput.imeVisible()) {
            // Typing is parked-only on the car even while browsing is allowed; nothing feeds the
            // vehicle state into this window unless AutoBridge runs on the car (UNKNOWN).
            keyInput.hide()
            showTypingRefusal()
        }
    }

    /** Car entry: why the keyboard does not come up (moving, or no vehicle state). */
    private fun showTypingRefusal() {
        statusText.text = getString(KeyboardTypingGate.refusalMessage())
        statusText.visibility = View.VISIBLE
        statusText.removeCallbacks(hideRefusal)
        statusText.postDelayed(hideRefusal, REFUSAL_MS)
    }

    private val hideRefusal = Runnable {
        val phase = session?.status?.value?.phase
        if (phase != DesktopSession.Phase.STARTING && phase != DesktopSession.Phase.FAILED) statusText.visibility = View.GONE
    }

    /** Rail Type/Address: the phone's keyboard for [target], or why it cannot come up. */
    private fun openKeyboard(target: TypingTarget) {
        if (target == TypingTarget.FIELD) refocusAddressAfterResize = false
        if (!keyInput.typingAllowed()) {
            showTypingRefusal()
            return
        }
        keyInput.show(target)
    }

    /**
     * A tap on the page while the keyboard is down: if Chrome now shows the text cursor under the
     * pointer (a field was tapped), the keyboard comes up for it. Chrome tells nobody that a field
     * has focus here, so the cursor is the hint; a tap on plain selectable text looks the same.
     */
    private fun afterPageTap() {
        if (!keyInput.typingAllowed() || keyInput.imeVisible()) return
        desktopView.removeCallbacks(probeTextCursor)
        desktopView.postDelayed(probeTextCursor, TEXT_CURSOR_PROBE_MS)
    }

    private val probeTextCursor = Runnable {
        session?.input?.keyboard?.probeTextCursor { text ->
            if (text && resumed && allowed() && keyInput.typingAllowed() && !keyInput.imeVisible()) openKeyboard(TypingTarget.FIELD)
        }
    }

    private fun attach(s: DesktopSession) {
        session = s
        rail.dragHeld = false
        desktopView.renderer = s.renderer
        rail.area = { s.renderer.mapping?.let { m -> android.graphics.RectF(m.offsetX, m.offsetY, m.offsetX + m.drawWidth, m.offsetY + m.drawHeight) } }
        s.renderer.overlay = rail
        rail.onAutoHide = { session?.renderer?.requestFrame() }
        s.renderer.placeholderText = getString(dev.autobridge.desktop.R.string.desktop_starting)
        scope.launch {
            s.status.collect { status ->
                when (status.phase) {
                    DesktopSession.Phase.FAILED -> {
                        statusText.visibility = View.VISIBLE
                        statusText.text = status.message?.take(500)
                    }
                    DesktopSession.Phase.STARTING -> {
                        statusText.visibility = View.VISIBLE
                        statusText.text = getString(dev.autobridge.desktop.R.string.desktop_starting)
                    }
                    else -> statusText.visibility = View.GONE
                }
            }
        }
    }

    /** The rail's handle, panel and More sheet first; everything else is the page. */
    private fun onPageTouch(event: MotionEvent): Boolean {
        if (!allowed()) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            railPressed = when (val tap = rail.onTap(event.x, event.y)) {
                is ControlRail.Tap.Perform -> {
                    onRailAction(tap.action)
                    true
                }
                ControlRail.Tap.Consumed -> true
                ControlRail.Tap.PassThrough -> false
            }
            if (railPressed) {
                session?.renderer?.burst()
                return true
            }
        }
        if (railPressed) return true
        // With Drag held (rail), a drag moves the held pointer as usual and a tap drops it.
        if (rail.dragHeld) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { heldDownX = event.x; heldDownY = event.y }
                MotionEvent.ACTION_UP -> if (kotlin.math.hypot(event.x - heldDownX, event.y - heldDownY) < tapSlop) {
                    session?.input?.releaseAtCursor()
                    rail.dragHeld = false
                    session?.renderer?.burst()
                    return true
                }
            }
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                // A drag that ended: let the mapper see it as a move, not a tap.
                session?.renderer?.burst()
                return true
            }
        }
        trackTap(event)
        return gestures.onTouchEvent(event)
    }

    /** Spots a plain tap (one finger, no move, not a long press) for [afterPageTap]. */
    private fun trackTap(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapCandidate = true
                tapDownX = event.x
                tapDownY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> tapCandidate = false
            MotionEvent.ACTION_MOVE -> if (kotlin.math.hypot(event.x - tapDownX, event.y - tapDownY) > tapSlop) tapCandidate = false
            MotionEvent.ACTION_UP -> {
                if (tapCandidate && event.eventTime - event.downTime < android.view.ViewConfiguration.getLongPressTimeout()) afterPageTap()
                tapCandidate = false
            }
        }
    }

    private var tapCandidate = false
    private var tapDownX = 0f
    private var tapDownY = 0f

    private var railPressed = false
    private var refocusAddressAfterResize = false
    private var heldDownX = 0f
    private var heldDownY = 0f
    private val tapSlop by lazy { android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat() }

    /** What the rail asked for; the rail has already changed its own state. */
    private fun onRailAction(action: ControlRail.Action) {
        if (!allowed() && action != ControlRail.Action.CLOSE) return
        val input = session?.input
        when (action) {
            ControlRail.Action.BACK -> input?.back()
            ControlRail.Action.FORWARD -> input?.forward()
            ControlRail.Action.RELOAD -> input?.reload()
            ControlRail.Action.NEW_TAB -> input?.newTab()
            ControlRail.Action.ZOOM_IN -> input?.key('='.code, Keysym.CONTROL_L)
            ControlRail.Action.ZOOM_OUT -> input?.key('-'.code, Keysym.CONTROL_L)
            ControlRail.Action.RESET_ZOOM -> input?.key('0'.code, Keysym.CONTROL_L)
            // The phone's own keyboard types into the address bar once Ctrl+L (show(ADDRESS),
            // through the bridge) has focused it.
            ControlRail.Action.ADDRESS -> {
                // Opening the keyboard shrinks the browser window, and Chromium drops the
                // address bar's focus on that resize; focus it again once the resize is done.
                // With the keyboard already up there is no resize, so nothing to wait for.
                val resizes = !keyInput.imeVisible()
                openKeyboard(TypingTarget.ADDRESS)
                if (keyInput.typingAllowed() && resizes) refocusAddressAfterResize = true
            }
            ControlRail.Action.KEYBOARD -> openKeyboard(TypingTarget.FIELD)
            ControlRail.Action.TAB,
            ControlRail.Action.ESCAPE,
            ControlRail.Action.SELECT_ALL,
            ControlRail.Action.COPY,
            ControlRail.Action.PASTE -> {
                if (!keyInput.typingAllowed()) {
                    showTypingRefusal()
                    return
                }
                val keys = input?.keyboard ?: return
                when (action) {
                    ControlRail.Action.TAB -> keys.key(Keysym.TAB)
                    ControlRail.Action.ESCAPE -> keys.key(Keysym.ESCAPE)
                    ControlRail.Action.SELECT_ALL -> keys.shortcut(Shortcut.SELECT_ALL)
                    ControlRail.Action.COPY -> keys.shortcut(Shortcut.COPY)
                    else -> {
                        // Paste what the phone copied: Android's clip goes to the guest first.
                        session?.clipboard?.pushAndroidClipboard(explicit = true)
                        keys.shortcut(Shortcut.PASTE)
                    }
                }
            }
            ControlRail.Action.CLOSE -> finish()
            ControlRail.Action.DRAG_START -> input?.pressAtCursor()
            ControlRail.Action.DRAG_END -> input?.releaseAtCursor()
            ControlRail.Action.MODE_CHANGED -> {
                if (rail.dragHeld) { input?.releaseAtCursor(); rail.dragHeld = false }
                if (rail.mode == PointerMode.MOUSE) {
                    session?.renderer?.mapping?.let { m -> input?.moveTo(m.displayWidth / 2, m.displayHeight / 2) }
                }
            }
            ControlRail.Action.KEYBOARD_AUTOBRIDGE,
            ControlRail.Action.KEYBOARD_CAR,
            ControlRail.Action.KEYBOARD_PHONE -> Unit
        }
    }

    private var resumed = false

    override fun onPause() {
        super.onPause()
        resumed = false
        clipWatch?.close()
        clipWatch = null
        desktopView.removeCallbacks(probeTextCursor)
        // Detach only if the renderer still draws into this view; the template car screen may
        // have taken it over (attachSurface), and then its surface must stay.
        if (session?.renderer?.detachView(desktopView) == true) DesktopEngine.noteSurfaceDetached()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // The engine may have moved on (the car restarted the browser) while this was away.
        DesktopEngine.session.value?.let { s -> if (s !== session && s.adapter.id == browserId && session != null) attach(s) }
        if (session != null) fit()
        if (clipWatch == null) clipWatch = ClipboardSync.watch(this) { session?.clipboard }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android only lets a focused app read the clipboard: hand what the phone copied to the guest.
        if (hasFocus) session?.clipboard?.pushAndroidClipboard()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // An instance started on the phone can be brought to the car under the alias's name.
        carEntry = DesktopCarEntry.isCarEntry(intent.component?.className)
        keyInput.gated = carEntry
        applyGate()
    }

    override fun onBackPressed() {
        // Nothing to go back in while the gate is closed.
        if (!allowed()) { finish(); return }
        // Back goes to the previous page in the browser; the menu's Close leaves.
        session?.input?.back() ?: super.onBackPressed()
    }

    override fun onDestroy() {
        ParkingStateStore.removeListener(parkingListener)
        BypassPolicyStore.removeListener(bypassListener)
        scope.cancel()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        private const val REFUSAL_MS = 4_000L
        /** After a page tap, how long Chrome gets to update the cursor under the pointer. */
        private const val TEXT_CURSOR_PROBE_MS = 250L
        private const val EXTRA_BROWSER = "browser"
        private const val EXTRA_URL = "url"

        fun intent(context: Context, id: DesktopBrowserId, url: String? = null): Intent =
            Intent(context, DesktopBrowserActivity::class.java)
                .putExtra(EXTRA_BROWSER, id.key)
                .apply { url?.let { putExtra(EXTRA_URL, it) } }
    }
}
