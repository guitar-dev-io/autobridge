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
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.autobridge.R
import dev.autobridge.desktop.DesktopEngine
import dev.autobridge.desktop.browser.DesktopBrowserId
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
 * The desktop browser on the phone: the X display drawn into a SurfaceView, with touch turned
 * into mouse input and the phone keyboard into key events. The browser's own toolbar (tabs,
 * address bar, back/forward) is the navigation; the small bar here only adds what a touch
 * screen lacks — a keyboard, back, reload — and a way out.
 *
 * Leaving the screen only detaches the surface; the browser keeps its tabs and is stopped after
 * [DesktopEngine.IDLE_STOP_MS] if nobody comes back.
 */
class DesktopBrowserActivity : Activity(), SurfaceHolder.Callback {
    private val scope = MainScope()
    private var session: DesktopSession? = null
    private lateinit var surfaceView: SurfaceView
    private lateinit var keyInput: KeyInputView
    private lateinit var statusText: TextView
    private lateinit var gestures: TouchGestureMapper

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.rebase(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DesktopEngine.init(this)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val id = DesktopBrowserId.fromKey(intent.getStringExtra(EXTRA_BROWSER))
            ?: DesktopEngine.chosenInstalledBrowser()?.id
        if (id == null || !DesktopEngine.state(id).state.installed) {
            Toast.makeText(this, R.string.desktop_settings_install_first, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        // The phone display is a laptop-sized 1280x720 at scale 1; it is scaled up to the phone.
        session = try {
            DesktopEngine.openSession(id, PHONE_WIDTH, PHONE_HEIGHT, 160, intent.getStringExtra(EXTRA_URL))
        } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        session?.renderer?.placeholderText = getString(dev.autobridge.desktop.R.string.desktop_starting)
        gestures = TouchGestureMapper(this, { session?.input }, { session?.renderer?.mapping }) { session?.renderer?.burst() }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surfaceView = SurfaceView(this).apply {
            holder.addCallback(this@DesktopBrowserActivity)
            setOnTouchListener { _, event -> gestures.onTouchEvent(event) }
        }
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        keyInput = KeyInputView(this) { session }
        root.addView(keyInput, FrameLayout.LayoutParams(1, 1))
        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setBackgroundColor(AutoBridgeDesign.tint(Color.BLACK, 0.7f))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
        }
        root.addView(statusText, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(12), 0, 0, dp(12)) })
        toolbar = toolbar()
        root.addView(toolbar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(32) })
        setContentView(root)
        hideSystemBars()
        // With the keyboard up, fit the page into the space above it instead of typing blind
        // under the keyboard; the bar moves up with it.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
            if (ime != imeHeight) {
                imeHeight = ime
                placeToolbar()
            }
            insets
        }

        scope.launch {
            session?.status?.collect { status ->
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

    private lateinit var toolbar: LinearLayout

    /**
     * The page is letterboxed into the surface (16:9 display), so the bar sits in the spare band:
     * a rail on the right when the bands are at the sides, a row under the picture when they are
     * above and below. Decided from the real surface size, not the configuration: a device that
     * ignores the landscape request (large screens, Android 16, some emulators) still reports
     * landscape while showing portrait.
     */
    private fun placeToolbar(surfaceWidth: Int = surfaceView.width, surfaceHeight: Int = surfaceView.height) {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        val sideBands = surfaceWidth.toFloat() / surfaceHeight > PHONE_WIDTH.toFloat() / PHONE_HEIGHT
        toolbar.orientation = if (sideBands) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        toolbar.layoutParams = if (sideBands) {
            FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(6) }
        } else {
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(8) + imeHeight }
        }
        applyContentArea(surfaceWidth, surfaceHeight, sideBands)
    }

    private var imeHeight = 0

    /**
     * The page is drawn only in what the bar and the keyboard leave free, so neither ever covers
     * it — even when the band around the 16:9 picture is thinner than the bar (a window the
     * system letterboxed, a squarish tablet).
     */
    private fun applyContentArea(w: Int, h: Int, sideBands: Boolean) {
        toolbar.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val gap = dp(14)
        if (sideBands) {
            session?.setContentArea(0, 0, (w - toolbar.measuredWidth - gap).coerceAtLeast(w / 2), (h - imeHeight).coerceAtLeast(h / 3))
        } else {
            session?.setContentArea(0, 0, w, (h - imeHeight - toolbar.measuredHeight - gap).coerceAtLeast(h / 3))
        }
    }

    private fun toolbar(): LinearLayout = LinearLayout(this).apply {
        background = AutoBridgeDesign.surface(this@DesktopBrowserActivity, AutoBridgeDesign.tint(AutoBridgeDesign.INK, 0.82f), 22)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        alpha = 0.9f
        fun glyph(text: String, description: Int, action: () -> Unit) = addView(
            AutoBridgeDesign.glyphButton(this@DesktopBrowserActivity, text, 20f) { action(); session?.renderer?.burst() }.apply {
                contentDescription = getString(description)
            },
            LinearLayout.LayoutParams(dp(44), dp(44))
        )
        glyph("⌨", R.string.desktop_viewer_keyboard) { keyInput.toggle() }
        glyph("‹", R.string.desktop_viewer_back) { session?.input?.back() }
        glyph("⟳", R.string.desktop_viewer_reload) { session?.input?.reload() }
        glyph("✕", R.string.desktop_viewer_close) { finish() }
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        session?.attachSurface(holder.surface, width, height)
        placeToolbar(width, height)
        DesktopEngine.noteSurfaceAttached()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        session?.detachSurface()
        DesktopEngine.noteSurfaceDetached()
    }

    override fun onBackPressed() {
        // Back goes to the previous page in the browser; the ✕ button leaves.
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
        private const val PHONE_WIDTH = 1280
        private const val PHONE_HEIGHT = 720

        fun intent(context: Context, id: DesktopBrowserId, url: String? = null): Intent =
            Intent(context, DesktopBrowserActivity::class.java)
                .putExtra(EXTRA_BROWSER, id.key)
                .apply { url?.let { putExtra(EXTRA_URL, it) } }
    }
}
