package dev.autobridge.projection

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView

/**
 * A WebView that never raises the system keyboard on the car display.
 *
 * Tapping a search box on the page gave the WebView an editable focus, and the car host answered
 * with its own IME. That keyboard has nothing to commit into — the projected page is never given
 * a working input connection (see `.agents/tasks/car-keyboard-focus/report.md`) — so the driver
 * typed into a dead box and then had to close it before the app's own keyboard was any use. Text
 * entry on this route belongs to [ProjectionBrowserActivity]'s keyboard, which the activity opens
 * itself when a tap lands on a page field.
 */
class CarPageWebView(context: Context) : WebView(context) {
    override fun onCheckIsTextEditor(): Boolean = false

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? = null
}
