package dev.autobridge.browser

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.view.WindowInsetsCompat

/**
 * The invisible, focused editable View that owns a real [InputConnection] inside the existing
 * hardware [CarHardwareWebWindow]. Modeled on `DesktopBrowserActivity.KeyInputView`, but it commits
 * into the car browser's own text plumbing: the connection is NON-BACKED
 * ([BaseInputConnection]`(this, false)`) with the app's [BrowserTextBuffer] authoritative, so the
 * IME's view of the field is seeded and continuously fed from the buffer via `initialSel*` +
 * `getTextBefore/AfterCursor`, and every raw edit is forwarded to the renderer which folds it into
 * the buffer with `applyImeEdit`.
 *
 * Text is committed, never composed (Thai resolves into the field instead of stranding in the
 * candidate strip), and `sendKeyEvent` routes DEL/Enter/printable keys rather than blanket-consuming.
 *
 * The view also owns the session-lifetime insets observation: while it is attached, a registered
 * [View.OnApplyWindowInsetsListener] on the window decor view reports `WindowInsets.Type.ime()`
 * visibility back via [onImeVisibilityChanged], so the probe can confirm the IME appeared and a
 * direct Gboard dismissal closes the session (finding 1).
 */
internal class CarImeInputView(
    context: Context,
    // Reads of the current field state the IME needs to align its own counts to the app buffer.
    private val textBeforeCursor: () -> CharSequence, // buffer.text up to buffer.cursor
    private val textAfterCursor: () -> CharSequence, // buffer.text from buffer.cursor
    private val selectionStart: () -> Int,
    private val selectionEnd: () -> Int,
    // Edit operations, applied by the renderer to the authoritative BrowserTextBuffer.
    private val onCommit: (String) -> Unit, // commitText: replace selection with text
    private val onDeleteBefore: (Int) -> Unit, // deleteSurroundingText(before): delete N cp before cursor
    private val onAction: () -> Unit, // IME_ACTION_GO / Enter
    // Reports WindowInsets.Type.ime() visibility while this view is attached (probe + dismiss detection).
    private val onImeVisibilityChanged: (Boolean) -> Unit,
) : View(context) {

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    /** Registered on the window decor view for the life of this view; drives probe + dismiss detection. */
    // WindowInsetsCompat: the platform's ime() visibility is API 30, and the app runs from API 29.
    private val insetsListener = OnApplyWindowInsetsListener { view, insets ->
        onImeVisibilityChanged(WindowInsetsCompat.toWindowInsetsCompat(insets, view).isVisible(WindowInsetsCompat.Type.ime()))
        insets
    }

    /** Registers the insets listener on the given decor view (the renderer passes `windowView`). */
    fun observeImeInsets(decorView: View?) {
        decorView?.setOnApplyWindowInsetsListener(insetsListener)
    }

    /** Removes the insets listener from the given decor view when the session ends. */
    fun stopObservingImeInsets(decorView: View?) {
        decorView?.setOnApplyWindowInsetsListener(null)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_GO
        // Seed the IME's model so its deleteSurroundingText counts and cursor arithmetic line up
        // with the app buffer (including the ADDRESS select-all seed state).
        outAttrs.initialSelStart = selectionStart()
        outAttrs.initialSelEnd = selectionEnd()
        return object : BaseInputConnection(this, false) {
            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence =
                textBeforeCursor().let { it.subSequence(maxOf(0, it.length - n), it.length) }

            override fun getTextAfterCursor(n: Int, flags: Int): CharSequence =
                textAfterCursor().let { it.subSequence(0, minOf(n, it.length)) }

            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                onCommit(text.toString())
                return true
            }

            // Thai: commit, don't compose, so multi-stage scripts land in the field.
            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean =
                commitText(text, newCursorPosition)

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // `after` is dropped: this EditorInfo has no extracted/fullscreen UI and the buffer
                // reports its cursor collapsed at the end (or the selection reported via initialSel*),
                // so a well-behaved IME only ever deletes before the cursor. See design finding 6.
                onDeleteBefore(beforeLength.coerceAtMost(64))
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                onAction()
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action != KeyEvent.ACTION_DOWN) return true
                return when (event.keyCode) {
                    KeyEvent.KEYCODE_DEL -> {
                        onDeleteBefore(1)
                        true
                    }

                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        onAction()
                        true
                    }

                    else -> {
                        val ch = event.unicodeChar
                        if (ch != 0) {
                            onCommit(String(Character.toChars(ch)))
                            true
                        } else {
                            super.sendKeyEvent(event) // do not blanket-consume unhandled keys
                        }
                    }
                }
            }
        }
    }
}
