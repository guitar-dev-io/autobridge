package dev.autobridge.projection

import android.content.Context
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView

/**
 * A WebView that never raises the system keyboard on the car display, and keeps its page's audio
 * playing while the car shows something else.
 *
 * ## Keyboard
 *
 * Tapping a search box on the page gave the WebView an editable focus, and the car host answered
 * with its own IME. That keyboard has nothing to commit into — the projected page is never given
 * a working input connection (see `.agents/tasks/car-keyboard-focus/report.md`) — so the driver
 * typed into a dead box and then had to close it before the app's own keyboard was any use. Text
 * entry on this route belongs to [ProjectionBrowserActivity]'s keyboard, which the activity opens
 * itself when a tap lands on a page field.
 *
 * ## Audio while hidden
 *
 * Opening Maps (or anything else) takes the car display away from this activity, and the platform
 * tells the WebView its window is no longer visible. Chromium then treats the page as a background
 * tab and suspends its `<video>` — YouTube and YouTube Music play through one — so the music
 * stopped and the car's media card sat on a paused song. [dev.autobridge.browser.BackgroundPlaybackMode]
 * already hides the change from the page's own script; this hides it from Chromium, which is below
 * anything a script can reach. Only visibility inherited from the window or a parent is masked:
 * hiding this view itself (the activity does, when the safety gate blocks the browser) still
 * hides the page.
 */
class CarPageWebView(context: Context) : WebView(context) {
    override fun onCheckIsTextEditor(): Boolean = false

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? = null

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(View.VISIBLE)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, if (changedView === this) visibility else View.VISIBLE)
    }
}
