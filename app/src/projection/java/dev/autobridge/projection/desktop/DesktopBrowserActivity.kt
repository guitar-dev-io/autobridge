package dev.autobridge.projection.desktop

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import dev.autobridge.R
import dev.autobridge.desktop.DesktopEngine
import dev.autobridge.desktop.browser.DesktopBrowserId
import dev.autobridge.desktop.browser.DesktopBrowsers
import dev.autobridge.desktop.display.DesktopView
import dev.autobridge.desktop.input.ControlRail
import dev.autobridge.desktop.input.Keysym
import dev.autobridge.desktop.input.TouchGestureMapper
import dev.autobridge.desktop.session.DesktopSession
import dev.autobridge.i18n.AppLocale
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
 * Leaving the screen only detaches the view; the browser keeps its tabs and is stopped after
 * [DesktopEngine.IDLE_STOP_MS] if nobody comes back.
 */
class DesktopBrowserActivity : Activity() {
    private val scope = MainScope()
    private var session: DesktopSession? = null
    private lateinit var browserId: DesktopBrowserId
    private lateinit var desktopView: DesktopView
    private lateinit var keyInput: KeyInputView
    private lateinit var statusText: TextView
    private lateinit var gestures: TouchGestureMapper
    private lateinit var rail: ControlRail
    private var imeHeight = 0

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.rebase(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DesktopEngine.init(this)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
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
        keyInput = KeyInputView(this) { session }
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
        desktopView.setOnTouchListener { _, event -> onPageTouch(event) }
        desktopView.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) fit()
        }
        // The keyboard takes the bottom of the screen: the browser window shrinks to what is left
        // above it, so the field being typed in stays visible.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
            if (ime != imeHeight) {
                imeHeight = ime
                fit()
            }
            insets
        }
    }

    /** Opens or re-fits the session for the view's current size and keyboard. */
    private fun fit() {
        val w = desktopView.width
        val h = desktopView.height - imeHeight
        if (w <= 0 || h <= 0) return
        val (vw, vh) = DesktopEngine.phoneViewport(w, desktopView.height).let { (fw, fh) ->
            fw to (fh * h / desktopView.height)
        }
        val s = session ?: try {
            DesktopEngine.openSession(browserId, vw, vh, 1f, intent.getStringExtra(EXTRA_URL)).also { attach(it) }
        } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        s.fitTo(vw, vh)
        s.renderer.setArea(android.graphics.Rect(0, 0, w, h))
        s.renderer.attachView(desktopView, desktopView.width, desktopView.height)
        DesktopEngine.noteSurfaceAttached()
    }

    private fun attach(s: DesktopSession) {
        session = s
        desktopView.renderer = s.renderer
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
        return gestures.onTouchEvent(event)
    }

    private var railPressed = false

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
            // The phone's own keyboard types into the address bar once Ctrl+L has focused it.
            ControlRail.Action.ADDRESS -> {
                input?.focusAddressBar()
                keyInput.toggle()
            }
            ControlRail.Action.KEYBOARD -> keyInput.toggle()
            ControlRail.Action.CLOSE -> finish()
            ControlRail.Action.MODE_CHANGED,
            ControlRail.Action.KEYBOARD_AUTOBRIDGE,
            ControlRail.Action.KEYBOARD_CAR -> Unit
        }
    }

    override fun onPause() {
        super.onPause()
        session?.detachSurface()
        DesktopEngine.noteSurfaceDetached()
    }

    override fun onResume() {
        super.onResume()
        if (session != null) fit()
    }

    override fun onBackPressed() {
        // Back goes to the previous page in the browser; the menu's Close leaves.
        session?.input?.back() ?: super.onBackPressed()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * An invisible view that owns the soft keyboard. Text is committed straight away (no
     * composing), so every character goes to the browser as it is typed, Thai included.
     */
    private class KeyInputView(context: Context, private val session: () -> DesktopSession?) : View(context) {
        private var shown = false

        init {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        fun toggle() {
            val imm = context.getSystemService(InputMethodManager::class.java)
            shown = !shown
            if (shown) {
                requestFocus()
                imm.showSoftInput(this, 0)
            } else {
                imm.hideSoftInputFromWindow(windowToken, 0)
            }
        }

        override fun onCheckIsTextEditor(): Boolean = true

        /** Hardware and Bluetooth keyboards (and `adb shell input`) arrive here, not through the IME. */
        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            val input = session()?.input ?: return super.onKeyDown(keyCode, event)
            val keysym = keysymFor(keyCode)
            when {
                keysym != null -> input.key(keysym)
                event.unicodeChar != 0 -> input.typeText(String(Character.toChars(event.unicodeChar)))
                else -> return super.onKeyDown(keyCode, event)
            }
            session()?.renderer?.burst()
            return true
        }

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_GO
            return object : BaseInputConnection(this, false) {
                override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                    session()?.input?.typeText(text.toString())
                    session()?.renderer?.burst()
                    return true
                }

                override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean =
                    commitText(text, newCursorPosition)

                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    repeat(beforeLength.coerceAtMost(64)) { session()?.input?.key(Keysym.BACKSPACE) }
                    repeat(afterLength.coerceAtMost(64)) { session()?.input?.key(Keysym.DELETE) }
                    session()?.renderer?.burst()
                    return true
                }

                override fun performEditorAction(actionCode: Int): Boolean {
                    session()?.input?.key(Keysym.RETURN)
                    session()?.renderer?.burst()
                    return true
                }

                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    if (event.action != KeyEvent.ACTION_DOWN) return true
                    val keysym = keysymFor(event.keyCode)
                    when {
                        keysym != null -> session()?.input?.key(keysym)
                        event.unicodeChar != 0 -> session()?.input?.typeText(String(Character.toChars(event.unicodeChar)))
                        else -> return super.sendKeyEvent(event)
                    }
                    session()?.renderer?.burst()
                    return true
                }
            }
        }
    }

    companion object {
        private fun keysymFor(keyCode: Int): Int? = when (keyCode) {
            KeyEvent.KEYCODE_DEL -> Keysym.BACKSPACE
            KeyEvent.KEYCODE_FORWARD_DEL -> Keysym.DELETE
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Keysym.RETURN
            KeyEvent.KEYCODE_TAB -> Keysym.TAB
            KeyEvent.KEYCODE_ESCAPE -> Keysym.ESCAPE
            KeyEvent.KEYCODE_DPAD_LEFT -> Keysym.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Keysym.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> Keysym.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Keysym.DOWN
            KeyEvent.KEYCODE_PAGE_UP -> Keysym.PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> Keysym.PAGE_DOWN
            KeyEvent.KEYCODE_MOVE_HOME -> Keysym.HOME
            KeyEvent.KEYCODE_MOVE_END -> Keysym.END
            KeyEvent.KEYCODE_F5 -> Keysym.F5
            else -> null
        }

        private const val EXTRA_BROWSER = "browser"
        private const val EXTRA_URL = "url"

        fun intent(context: Context, id: DesktopBrowserId, url: String? = null): Intent =
            Intent(context, DesktopBrowserActivity::class.java)
                .putExtra(EXTRA_BROWSER, id.key)
                .apply { url?.let { putExtra(EXTRA_URL, it) } }
    }
}
