package dev.autobridge.projection.desktop

import android.content.Context
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import dev.autobridge.R
import dev.autobridge.desktop.DesktopLog
import dev.autobridge.desktop.input.Keysym
import dev.autobridge.desktop.keyboard.AndroidKeyMap
import dev.autobridge.desktop.keyboard.KeyAction
import dev.autobridge.desktop.keyboard.KeyboardInputBridge
import dev.autobridge.desktop.keyboard.Shortcut
import dev.autobridge.desktop.keyboard.TypingTarget
import dev.autobridge.desktop.session.DesktopSession
import java.text.BreakIterator

/**
 * An invisible view that owns the phone's soft keyboard and types into Chrome through the
 * session's [KeyboardInputBridge]: composing text (Thai prediction, suggestions) is corrected by
 * diff instead of typed twice, deletions count code points, Gboard's Select all / Copy / Paste
 * reach the page, and hardware keyboards keep their Ctrl/Alt chords.
 *
 * The [InputConnection]'s editable is a mirror of what was typed since the last reset (a page
 * tap, a focus-moving key, a new session: the bridge's reset listeners), so the IME sees the word
 * it is composing; it never holds text the bridge dropped.
 *
 * [gated]: started as the car entry or the phone companion, typing is parked-only
 * ([KeyboardTypingGate]); the host also hides the keyboard when the gate closes. [companion]: the
 * phone companion for the car session.
 */
class ChromeKeyInputView(
    context: Context,
    private val session: () -> DesktopSession?,
    @Volatile var gated: Boolean,
    private val companion: Boolean = false,
) : View(context) {
    var target = TypingTarget.FIELD
        private set

    /** The mirror changed (the companion echoes it). */
    var onMirrorChanged: ((CharSequence) -> Unit)? = null

    private val imm = context.getSystemService(InputMethodManager::class.java)
    private val mirror: Editable = SpannableStringBuilder()

    /** The keyboard was asked for and not dismissed since: focus and window changes bring it back. */
    private var wantIme = false
    private var showTries = 0

    private val resetListener: () -> Unit = { resetMirror() }

    /** Only the window the user is typing in says so (a viewer left behind the companion stays quiet). */
    private val pasteRefusedListener: () -> Unit = {
        if (hasWindowFocus()) Toast.makeText(context, R.string.desktop_keyboard_paste_too_long, Toast.LENGTH_SHORT).show()
    }

    /**
     * Shows the keyboard once the view is really the IME's target. Right after [requestFocus],
     * from inside a touch dispatch, the input method has not started input on this view yet and
     * showSoftInput is silently ignored (Android 11+): the old view called it there and the
     * keyboard never came up. So it is posted, and retried until the IME accepts it.
     */
    private val showIme = object : Runnable {
        override fun run() {
            if (!wantIme || !isAttachedToWindow) return
            if (!isFocused) requestFocus()
            val shown = hasWindowFocus() && imm.showSoftInput(this@ChromeKeyInputView, 0)
            if (shown) return
            if (++showTries < SHOW_TRIES) {
                postDelayed(this, SHOW_RETRY_MS)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isFocused) {
                windowInsetsController?.show(WindowInsets.Type.ime())
            } else {
                DesktopLog.i("phone keyboard: input method did not show")
            }
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        contentDescription = context.getString(R.string.desktop_keyboard_input_description)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        Selection.setSelection(mirror, 0)
    }

    /**
     * Car entry and companion: only while parked. The DesktopBrowserPane alias (a Multi-app pane)
     * resolves to `carEntry = false`, so it is ungated like the phone viewer, as before.
     */
    fun typingAllowed(): Boolean = !gated || KeyboardTypingGate.allowed()

    private fun bridge(): KeyboardInputBridge? = session()?.input?.keyboard

    private fun burst() {
        session()?.renderer?.burst()
    }

    /**
     * Brings the keyboard up for [target]. [prepare]: a typing session the user started (rail
     * Type/Address, a new companion intent): gives Chrome X focus and, for the address bar,
     * Ctrl+L. A re-show (resume, gate reopened) passes false and leaves Chrome and the mirror as
     * they are.
     */
    fun show(target: TypingTarget, prepare: Boolean = true) {
        if (!typingAllowed()) return
        val changed = target != this.target
        this.target = target
        if (prepare) {
            bridge()?.prepareForTyping(target)
            clearMirror()
        }
        wantIme = true
        if (isFocused) {
            if (changed || prepare) imm.restartInput(this)
        } else {
            requestFocus()
        }
        showTries = 0
        removeCallbacks(showIme)
        post(showIme)
    }

    fun toggle() = if (imeVisible()) hide() else show(TypingTarget.FIELD)

    fun hide() {
        wantIme = false
        removeCallbacks(showIme)
        imm.hideSoftInputFromWindow(windowToken, 0)
        endComposing()
    }

    /** The keyboard went away by itself (Back, its own hide key): the composed text stays as typed. */
    fun onImeHidden() {
        wantIme = false
        removeCallbacks(showIme)
        endComposing()
        resetMirror()
    }

    fun imeVisible(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) rootWindowInsets?.isVisible(WindowInsets.Type.ime()) ?: wantIme else wantIme

    private fun endComposing() {
        BaseInputConnection.removeComposingSpans(mirror)
        bridge()?.finishComposing()
    }

    private fun clearMirror() {
        mirror.clear()
        Selection.setSelection(mirror, 0)
        onMirrorChanged?.invoke(mirror)
    }

    /** Focus may have moved in Chrome: the IME starts over on an empty field. */
    private fun resetMirror() {
        clearMirror()
        if (isFocused) imm.restartInput(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        KeyboardInputBridge.addResetListener(resetListener)
        KeyboardInputBridge.addPasteRefusedListener(pasteRefusedListener)
    }

    override fun onDetachedFromWindow() {
        KeyboardInputBridge.removeResetListener(resetListener)
        KeyboardInputBridge.removePasteRefusedListener(pasteRefusedListener)
        removeCallbacks(showIme)
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && wantIme && typingAllowed()) {
            showTries = 0
            removeCallbacks(showIme)
            post(showIme)
        }
    }

    override fun onCheckIsTextEditor(): Boolean = true

    /** Hardware and Bluetooth keyboards (and `adb shell input`) while no IME consumes the keys. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val b = bridge()
        if (!typingAllowed() || b == null) return super.onKeyDown(keyCode, event)
        return if (sendKey(b, event)) true else super.onKeyDown(keyCode, event)
    }

    /** Sends [event] (a key down) to Chrome; false when it is not ours. */
    private fun sendKey(b: KeyboardInputBridge, event: KeyEvent): Boolean {
        val meta = event.metaState
        when (val action = AndroidKeyMap.map(event.keyCode, meta, event.getUnicodeChar(meta), event.getUnicodeChar(0))) {
            is KeyAction.Key -> {
                b.key(action.keysym, *action.modifiers)
                // Focus-moving keys reset through the bridge; others (Backspace, arrows) leave the
                // mirror out of step with Chrome, so it starts over too.
                if (!action.focusMove && mirror.isNotEmpty()) resetMirror()
            }
            is KeyAction.Text -> b.commitText(action.text)
            KeyAction.Consumed -> return true
            KeyAction.Unhandled -> return false
        }
        burst()
        return true
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Never VISIBLE_PASSWORD: keyboards drop Thai and composition for password fields.
        outAttrs.inputType = when (target) {
            TypingTarget.ADDRESS -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            TypingTarget.FIELD -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_GO
        outAttrs.initialSelStart = Selection.getSelectionStart(mirror).coerceAtLeast(0)
        outAttrs.initialSelEnd = Selection.getSelectionEnd(mirror).coerceAtLeast(0)
        return ChromeInputConnection()
    }

    /**
     * Every method checks the gate and the session before touching the mirror, so the mirror
     * never claims text the bridge dropped; dropped input still returns true (IMEs retry on false).
     */
    private inner class ChromeInputConnection : BaseInputConnection(this@ChromeKeyInputView, true) {
        override fun getEditable(): Editable = mirror

        private inline fun typing(what: String, block: (KeyboardInputBridge) -> Boolean): Boolean {
            val b = bridge()
            if (!typingAllowed() || b == null) {
                val where = if (companion) "companion" else "viewer"
                DesktopLog.d("phone keyboard input dropped ($where): $what (${if (b == null) "no session" else "not parked"})")
                return true
            }
            val result = block(b)
            burst()
            if (mirror.length > MAX_MIRROR) post { resetMirror() }
            onMirrorChanged?.invoke(mirror)
            return result
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean = typing("compose") { b ->
            super.setComposingText(text, newCursorPosition)
            b.setComposingText(text.toString())
            true
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean = typing("commit") { b ->
            super.commitText(text, newCursorPosition)
            b.commitText(text.toString())
            true
        }

        override fun finishComposingText(): Boolean = typing("finish") { b ->
            super.finishComposingText()
            b.finishComposing()
            true
        }

        /** The IME re-composes the word before the caret; it is already in Chrome. */
        override fun setComposingRegion(start: Int, end: Int): Boolean = typing("region") { b ->
            val s = minOf(start, end).coerceAtLeast(0)
            val e = maxOf(start, end).coerceAtMost(mirror.length)
            if (e != Selection.getSelectionEnd(mirror) || e - s > KeyboardInputBridge.MAX_COMPOSING) return@typing false
            super.setComposingRegion(s, e)
            b.adoptComposing(mirror.subSequence(s, e).toString())
            true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = typing("delete") { b ->
            if (beforeLength < 0 || afterLength < 0) return@typing false
            val start = Selection.getSelectionStart(mirror).coerceIn(0, mirror.length)
            val end = Selection.getSelectionEnd(mirror).coerceIn(start, mirror.length)
            // Within the mirror, UTF-16 units become code points; beyond it each unit is one.
            val inBefore = minOf(beforeLength, start)
            val before = Character.codePointCount(mirror, start - inBefore, start) + (beforeLength - inBefore)
            val inAfter = minOf(afterLength, mirror.length - end)
            val after = Character.codePointCount(mirror, end, end + inAfter) + (afterLength - inAfter)
            super.deleteSurroundingText(beforeLength, afterLength)
            if (before > 0) b.deleteBefore(before)
            if (after > 0) b.deleteAfter(after)
            true
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = typing("delete") { b ->
            if (beforeLength < 0 || afterLength < 0) return@typing false
            super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            if (beforeLength > 0) b.deleteBefore(beforeLength)
            if (afterLength > 0) b.deleteAfter(afterLength)
            true
        }

        /** Caret moves (Gboard's space-bar swipe) become Left/Right per grapheme; selections cannot be mirrored. */
        override fun setSelection(start: Int, end: Int): Boolean = typing("selection") { b ->
            if (start != end) return@typing false
            val from = Selection.getSelectionEnd(mirror).coerceIn(0, mirror.length)
            val to = start.coerceIn(0, mirror.length)
            if (from != to) {
                val steps = graphemesBetween(mirror.toString(), minOf(from, to), maxOf(from, to))
                val keysym = if (to < from) Keysym.LEFT else Keysym.RIGHT
                repeat(steps) { b.key(keysym) }
            }
            super.setSelection(to, to)
            true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN) {
                // Ups of keys handled on the way down are swallowed with them.
                val meta = event.metaState
                val mapped = AndroidKeyMap.map(event.keyCode, meta, event.getUnicodeChar(meta), event.getUnicodeChar(0))
                return if (mapped == KeyAction.Unhandled) super.sendKeyEvent(event) else true
            }
            return typing("key") { b -> sendKey(b, event) || super.sendKeyEvent(event) }
        }

        override fun performEditorAction(actionCode: Int): Boolean = typing("action") { b ->
            b.key(Keysym.RETURN)
            true
        }

        override fun performContextMenuAction(id: Int): Boolean = typing("menu") { b ->
            when (id) {
                android.R.id.selectAll -> b.shortcut(Shortcut.SELECT_ALL)
                android.R.id.cut -> b.shortcut(Shortcut.CUT)
                android.R.id.copy -> b.shortcut(Shortcut.COPY)
                android.R.id.paste, android.R.id.pasteAsPlainText -> {
                    // Paste what the phone copied: hand Android's clip to the guest first.
                    session()?.clipboard?.pushAndroidClipboard(explicit = true)
                    b.shortcut(Shortcut.PASTE)
                }
                android.R.id.undo -> b.shortcut(Shortcut.UNDO)
                android.R.id.redo -> b.shortcut(Shortcut.REDO)
                else -> return@typing false
            }
            true
        }
    }

    companion object {
        private const val MAX_MIRROR = 4096
        private const val SHOW_TRIES = 10
        private const val SHOW_RETRY_MS = 60L

        /** User-perceived characters between [from] and [to] (Thai clusters and emoji count once). */
        internal fun graphemesBetween(text: String, from: Int, to: Int): Int {
            if (to <= from) return 0
            val it = BreakIterator.getCharacterInstance()
            it.setText(text)
            var count = 0
            var pos = from
            while (pos < to) {
                pos = it.following(pos)
                if (pos == BreakIterator.DONE) break
                count++
            }
            return count
        }
    }
}
