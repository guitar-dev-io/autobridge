package dev.autobridge.desktop.keyboard

import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import dev.autobridge.desktop.DesktopLog
import dev.autobridge.desktop.display.X11Connection
import dev.autobridge.desktop.input.Keysym
import dev.autobridge.desktop.input.X11Keyboard
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Where typed text should land when a typing session starts. */
enum class TypingTarget { FIELD, ADDRESS }

enum class Shortcut { SELECT_ALL, COPY, CUT, PASTE, UNDO, REDO }

/**
 * Every keyboard front end (car keyboard, SearchTemplate, phone IME, rail Text row, debug
 * harness) types through this. All work runs on the session's `desktop-input` thread, in order
 * with clicks; every method returns at once.
 *
 * Chrome has no preedit channel here, so composing text is typed for real and corrected by
 * [CompositionDiff]. A commit that ends a composition is always diffed; other commits longer than
 * [PASTE_THRESHOLD] code points, or multi-line, are pasted through CLIPBOARD when available.
 *
 * A failed write (the X connection went away) marks the bridge broken: one WARN, later input is
 * dropped at DEBUG. The next session has a new bridge.
 */
class KeyboardInputBridge internal constructor(
    private val keys: () -> X11Keyboard,
    private val post: (() -> Unit) -> Unit,
    private val x: X11Connection,
    private val clipboard: () -> X11Clipboard?,
    private val sync: () -> ClipboardSync?,
) {
    enum class DebugRead { TITLE, CLIPBOARD, STATE }

    // Input thread only.
    private var composing = ""
    @Volatile private var broken = false
    private val warned = AtomicBoolean(false)

    /**
     * Once per user-started typing session: releases stray keys, ends composing, gives a Chrome
     * window X focus, and for [TypingTarget.ADDRESS] selects the omnibox (Ctrl+L).
     */
    fun prepareForTyping(target: TypingTarget) = submit("prepare") {
        val k = keys()
        k.releaseAll()
        composing = ""
        ensureXFocus()
        if (target == TypingTarget.ADDRESS) k.tapKeysym('l'.code, Keysym.CONTROL_L)
    }

    /** The IME's composing text: Chrome is brought from the last composing text to [text] by diff. */
    fun setComposingText(text: String) = submit("compose") {
        val t = TextSanitizer.sanitize(text)
        typeDiff(CompositionDiff.diff(composing, t))
        composing = if (CompositionDiff.codePoints(t) > MAX_COMPOSING) "" else t
    }

    /** The IME re-composes the word ending at the caret: it is already in Chrome, nothing is sent. */
    fun adoptComposing(text: String) = submit("adopt") {
        val t = TextSanitizer.sanitize(text)
        composing = if (CompositionDiff.codePoints(t) > MAX_COMPOSING) "" else t
    }

    fun commitText(text: String) = submit("commit") {
        val t = TextSanitizer.sanitize(text)
        when (val commit = CompositionDiff.commit(composing, t, PASTE_THRESHOLD)) {
            is CompositionDiff.Commit.Retype -> {
                typeDiff(commit.diff)
                composing = ""
            }
            is CompositionDiff.Commit.Paste -> paste(commit.text)
            is CompositionDiff.Commit.Type -> keys().typeRun(commit.text)
        }
    }

    /** The composing text stays as typed; no X traffic. */
    fun finishComposing() = submit("finish") { composing = "" }

    fun deleteBefore(codePoints: Int) = submit("delete") {
        composing = ""
        val k = keys()
        repeat(codePoints.coerceIn(0, MAX_DELETE)) { k.tapKeysym(Keysym.BACKSPACE) }
    }

    fun deleteAfter(codePoints: Int) = submit("delete") {
        composing = ""
        val k = keys()
        repeat(codePoints.coerceIn(0, MAX_DELETE)) { k.tapKeysym(Keysym.DELETE) }
    }

    fun key(keysym: Int, vararg modifiers: Int) {
        submit("key") {
            composing = ""
            keys().tapKeysym(keysym, *modifiers)
        }
        if (movesFocus(keysym, modifiers)) fireReset()
    }

    /** PASTE pastes the guest CLIPBOARD (Ctrl+V); push the Android clip first to paste that. */
    fun shortcut(s: Shortcut) {
        submit("shortcut") {
            composing = ""
            val k = keys()
            when (s) {
                Shortcut.SELECT_ALL -> k.tapKeysym('a'.code, Keysym.CONTROL_L)
                Shortcut.COPY -> { k.tapKeysym('c'.code, Keysym.CONTROL_L); afterCopy() }
                Shortcut.CUT -> { k.tapKeysym('x'.code, Keysym.CONTROL_L); afterCopy() }
                Shortcut.PASTE -> k.tapKeysym('v'.code, Keysym.CONTROL_L)
                Shortcut.UNDO -> k.tapKeysym('z'.code, Keysym.CONTROL_L)
                Shortcut.REDO -> k.tapKeysym('z'.code, Keysym.CONTROL_L, Keysym.SHIFT_L)
            }
        }
        fireReset()
    }

    /** Owns CLIPBOARD with [text], then Ctrl+V; typed instead when there is no clipboard. */
    fun pasteText(text: String) = submit("paste") { paste(TextSanitizer.sanitize(text)) }

    /** A click or focus-moving key: composing ends and the front ends reset their IME mirror. */
    fun noteFocusMayChange() {
        post { composing = "" }
        fireReset()
    }

    val clipboardAvailable: Boolean get() = clipboard()?.available == true

    /**
     * Whether Chrome shows the text cursor under the pointer now ([TextCursor]): the phone viewer
     * asks after a tap on the page, to bring up the keyboard for a text field. Runs on the input
     * thread (after the tap's click), [callback] on the main thread; false when it cannot tell.
     */
    fun probeTextCursor(callback: (Boolean) -> Unit) = post {
        val text = try {
            x.xfixesCursorImage()?.let { TextCursor.isText(it.width, it.height, it.xhot, it.yhot, it.pixels) } ?: false
        } catch (e: Exception) {
            DesktopLog.d("text cursor probe failed: ${DesktopLog.describe(e)}")
            false
        }
        mainDispatcher(Runnable { callback(text) })
    }

    /** Debug/test reads; each runs off the main thread and completes [callback] on the main thread. */
    @VisibleForTesting
    fun debugProbe(op: DebugRead, callback: (String) -> Unit) {
        val deliver = { result: String -> mainDispatcher(Runnable { callback(result) }) }
        if (op == DebugRead.CLIPBOARD) {
            val c = clipboard()
            if (c == null || !c.available) deliver(NONE) else c.readAsync { deliver(it ?: NONE) }
            return
        }
        post {
            val result = try {
                if (op == DebugRead.TITLE) title() else state()
            } catch (e: Exception) {
                "error:${DesktopLog.describe(e)}"
            }
            deliver(result)
        }
    }

    private fun paste(text: String) {
        composing = ""
        if (text.isEmpty()) return
        val c = clipboard()
        if (c == null || !c.available) {
            keys().typeRun(text)
            return
        }
        if (text.toByteArray(Charsets.UTF_8).size > c.maxOwnBytes) {
            DesktopLog.i("keyboard paste refused: text too long")
            firePasteRefused()
            // Nothing reached Chrome: the IME mirrors start over instead of showing the text.
            fireReset()
            return
        }
        // What the user had copied: given back once Chrome has fetched this paste, so a later
        // Paste still inserts their copy, not the IME's text. Taken from what is already known,
        // never asked of Chrome: a ConvertSelection to Chrome right before the Ctrl+V made Chrome
        // drop the paste on device.
        val previous = c.takePendingRestore() ?: c.knownText()
        if (c.own(text)) {
            sync()?.noteBridgeOwned()
            if (previous != null && previous != text) c.restoreAfterPaste(text, previous, post)
            keys().tapKeysym('v'.code, Keysym.CONTROL_L)
        } else {
            keys().typeRun(text)
        }
    }

    private fun afterCopy() {
        val c = clipboard() ?: return
        if (c.available && !c.xfixes) sync()?.scheduleReadAfterCopy()
    }

    private fun typeDiff(diff: CompositionDiff.Diff) {
        if (diff.isEmpty) return
        val k = keys()
        repeat(diff.backspaces) { k.tapKeysym(Keysym.BACKSPACE) }
        if (diff.insert.isNotEmpty()) k.typeRun(diff.insert)
    }

    /**
     * Gives a Chrome window X focus unless one (or a descendant) already has it. Without a window
     * manager the focus can be None/PointerRoot, and keys then go wherever the pointer is.
     */
    private fun ensureXFocus() {
        try {
            val root = x.setup.root
            val tops = topLevels()
            val largest = tops.maxByOrNull { it.width.toLong() * it.height } ?: run {
                DesktopLog.i("keyboard focus: no top-level window")
                return
            }
            val focus = x.getInputFocus()
            if (focus != X11Connection.FOCUS_NONE && focus != X11Connection.FOCUS_POINTER_ROOT && focus != root) {
                val ids = tops.map { it.id }.toSet()
                var w = focus
                for (level in 0 until MAX_FOCUS_DEPTH) {
                    if (w in ids) return
                    val parent = x.queryTreeParent(w)
                    if (parent == 0 || parent == root) break
                    w = parent
                }
            }
            x.setInputFocus(largest.id)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            DesktopLog.i("keyboard focus not set (${DesktopLog.describe(e)}); typing with the current focus")
        }
    }

    private fun topLevels(): List<X11Connection.WindowInfo> = x.queryTree().mapNotNull { id ->
        try {
            x.windowInfo(id)
        } catch (e: ExecutionException) {
            null // the window went away
        }
    }.filter { it.viewable && !it.overrideRedirect }

    private fun title(): String {
        val window = topLevels().maxByOrNull { it.width.toLong() * it.height }?.id ?: return ""
        val utf8 = x.getProperty(window, x.internAtom("_NET_WM_NAME"), x.internAtom("UTF8_STRING"), maxBytes = TITLE_BYTES)
        if (utf8.data.isNotEmpty()) return String(utf8.data, Charsets.UTF_8)
        val legacy = x.getProperty(window, X11Connection.ATOM_WM_NAME, X11Connection.ATOM_STRING, maxBytes = TITLE_BYTES)
        return String(legacy.data, Charsets.ISO_8859_1)
    }

    private fun state(): String {
        val k = keys()
        val focus = try { "0x" + Integer.toHexString(x.getInputFocus()) } catch (e: TimeoutException) { "?" }
        return "pool=${k.poolSize} pinned=${k.pinnedCount} bound=${k.boundCount} clipboard=$clipboardAvailable focus=$focus broken=$broken"
    }

    private fun submit(what: String, block: () -> Unit) = post {
        if (broken) {
            DesktopLog.d("keyboard input dropped: $what (connection lost)")
            return@post
        }
        try {
            block()
        } catch (e: IOException) {
            broken = true
            val message = "keyboard input dropped: ${DesktopLog.describe(e)}"
            if (warned.compareAndSet(false, true)) DesktopLog.w(message) else DesktopLog.d(message)
        }
    }

    companion object {
        /** Non-composing commits longer than this (code points) are pasted, not typed. */
        const val PASTE_THRESHOLD = 32
        const val MAX_COMPOSING = 2048
        const val MAX_DELETE = 2048
        private const val MAX_FOCUS_DEPTH = 8
        private const val TITLE_BYTES = 4096
        private const val NONE = "<none>"

        private val resetListeners = CopyOnWriteArrayList<() -> Unit>()
        private val pasteRefusedListeners = CopyOnWriteArrayList<() -> Unit>()
        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

        /** Where reset listeners and probe callbacks run; the main thread outside tests. */
        @VisibleForTesting
        internal var mainDispatcher: (Runnable) -> Unit = { mainHandler.post(it) }

        /**
         * Session-independent: a front end registers once (e.g. while its view is attached) and
         * hears every reset, whichever session is current. Called on the main thread.
         */
        fun addResetListener(listener: () -> Unit) { resetListeners.addIfAbsent(listener) }

        fun removeResetListener(listener: () -> Unit) { resetListeners.remove(listener) }

        /** Like [addResetListener]: told (main thread) when a paste was too large to send. */
        fun addPasteRefusedListener(listener: () -> Unit) { pasteRefusedListeners.addIfAbsent(listener) }

        fun removePasteRefusedListener(listener: () -> Unit) { pasteRefusedListeners.remove(listener) }

        internal fun firePasteRefused() {
            if (pasteRefusedListeners.isEmpty()) return
            mainDispatcher(Runnable { for (l in pasteRefusedListeners) l() })
        }

        /** Focus may have moved or the session changed: IME mirrors must be cleared. */
        internal fun fireReset() {
            if (resetListeners.isEmpty()) return
            mainDispatcher(Runnable { for (l in resetListeners) l() })
        }

        private fun movesFocus(keysym: Int, modifiers: IntArray): Boolean =
            keysym == Keysym.RETURN || keysym == Keysym.TAB || keysym == Keysym.ESCAPE ||
                keysym == Keysym.UP || keysym == Keysym.DOWN || keysym == Keysym.PAGE_UP || keysym == Keysym.PAGE_DOWN ||
                modifiers.any { it == Keysym.CONTROL_L || it == Keysym.CONTROL_R || it == Keysym.ALT_L || it == Keysym.ALT_R }
    }
}
