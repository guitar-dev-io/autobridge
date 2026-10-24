package dev.autobridge.projection.desktop

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.autobridge.R
import dev.autobridge.desktop.DesktopEngine
import dev.autobridge.desktop.keyboard.ClipboardSync
import dev.autobridge.desktop.keyboard.TypingTarget
import dev.autobridge.i18n.AppLocale
import dev.autobridge.remote.CarScreenController
import dev.autobridge.safety.BypassPolicyStore
import dev.autobridge.safety.ParkingStateStore
import dev.autobridge.ui.AutoBridgeDesign.dp

/**
 * The phone's keyboard for Chrome on the car (the car rail's "Phone" keyboard choice, opened from
 * [DesktopPhoneKeyboard]'s notification). A small screen with the keyboard up: what is typed goes
 * to the browser on the car through the same [ChromeKeyInputView] as the phone viewer, parked-only.
 *
 * It never attaches the renderer, so the car keeps its frames. It runs in its own task
 * (taskAffinity, singleTask): Done and the no-session path remove that task, so closing it never
 * resumes a phone viewer left behind, which would take the car's frames and resize Chrome.
 */
class DesktopPhoneKeyboardActivity : Activity() {
    private lateinit var keyInput: ChromeKeyInputView
    private lateinit var notice: TextView
    private lateinit var echo: TextView
    private var target = TypingTarget.FIELD
    /** A new launch intent: the next gated-open resume starts a typing session (Ctrl+L for the address). */
    private var pendingPrepare = false
    private var resumed = false
    private var clipWatch: AutoCloseable? = null

    private val parkingListener: (ParkingStateStore.State) -> Unit = { post { refresh() } }
    private val bypassListener: (BypassPolicyStore.State) -> Unit = { post { refresh() } }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.rebase(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DesktopEngine.init(this)
        target = DesktopPhoneKeyboard.targetOf(intent?.getStringExtra(DesktopPhoneKeyboard.EXTRA_TARGET))
        // A recreation (rotation) keeps the typing session; only a new intent starts one.
        pendingPrepare = savedInstanceState == null

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val title = TextView(this).apply {
            text = getString(R.string.desktop_keyboard_companion_title)
            textSize = 20f
            isAccessibilityHeading = true
        }
        root.addView(title)
        keyInput = ChromeKeyInputView(this, { DesktopEngine.session.value }, gated = true, companion = true)
        root.addView(keyInput, LinearLayout.LayoutParams(1, 1))
        echo = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(16), 0, dp(16))
            maxLines = 3
        }
        root.addView(echo, LinearLayout.LayoutParams(-1, -2))
        keyInput.onMirrorChanged = { echo.text = it.toString().takeLast(ECHO_CHARS) }
        notice = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(0xB3, 0x26, 0x1E))
            visibility = View.GONE
        }
        root.addView(notice, LinearLayout.LayoutParams(-1, -2))
        val done = Button(this).apply {
            text = getString(R.string.desktop_keyboard_companion_done)
            setOnClickListener { close() }
        }
        root.addView(done, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; topMargin = dp(8) })
        setContentView(root)

        ParkingStateStore.addListener(parkingListener)
        BypassPolicyStore.addListener(bypassListener)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        target = DesktopPhoneKeyboard.targetOf(intent.getStringExtra(DesktopPhoneKeyboard.EXTRA_TARGET))
        pendingPrepare = true
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        DesktopPhoneKeyboard.cancel(this)
        if (clipWatch == null) clipWatch = ClipboardSync.watch(this) { DesktopEngine.session.value?.clipboard }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        clipWatch?.close()
        clipWatch = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) DesktopEngine.session.value?.clipboard?.pushAndroidClipboard()
    }

    override fun onDestroy() {
        if (::notice.isInitialized) notice.removeCallbacks(closeWithoutSession)
        ParkingStateStore.removeListener(parkingListener)
        BypassPolicyStore.removeListener(bypassListener)
        super.onDestroy()
    }

    /** Re-runs the gate: keyboard up while parked with a session, else the reason and no input. */
    private fun refresh() {
        if (!::keyInput.isInitialized || isFinishing) return
        val message = when {
            DesktopEngine.session.value == null -> R.string.desktop_keyboard_no_session
            !KeyboardTypingGate.allowed() -> KeyboardTypingGate.refusalMessage(companionCarConnected = CarScreenController.isConnected)
            else -> null
        }
        if (message != null) {
            keyInput.hide()
            notice.text = getString(message)
            notice.visibility = View.VISIBLE
            // Nothing to type into: after the notice, the companion's own task goes away (R3-1).
            if (message == R.string.desktop_keyboard_no_session) {
                notice.removeCallbacks(closeWithoutSession)
                notice.postDelayed(closeWithoutSession, NO_SESSION_CLOSE_MS)
            }
            return
        }
        notice.removeCallbacks(closeWithoutSession)
        notice.visibility = View.GONE
        if (!resumed) return
        keyInput.show(target, prepare = pendingPrepare)
        pendingPrepare = false
    }

    private fun close() {
        keyInput.hide()
        finishAndRemoveTask()
    }

    private val closeWithoutSession = Runnable {
        if (!isFinishing && DesktopEngine.session.value == null) close()
    }

    private fun post(block: () -> Unit) {
        if (::keyInput.isInitialized) keyInput.post(block) else runOnUiThread(block)
    }

    private companion object {
        const val ECHO_CHARS = 200
        const val NO_SESSION_CLOSE_MS = 2500L
    }
}
