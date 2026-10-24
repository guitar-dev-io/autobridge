package dev.autobridge.desktop.keyboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.autobridge.desktop.DesktopLog

/**
 * Connects the guest CLIPBOARD ([X11Clipboard]) with Android's [ClipboardManager], text only.
 *
 * Android → guest: [pushAndroidClipboard] (main thread, only works while an app window has
 * focus) owns CLIPBOARD with the phone's clip. Guest → Android: Chrome's copies arrive through
 * [onGuestCopied] and become the primary clip (allowed from the background). A hash of the last
 * synced text stops the two directions echoing each other.
 *
 * Owned by the session; Android listeners belong to the activities ([watch]).
 */
class ClipboardSync(context: Context, private val x: X11Clipboard) : AutoCloseable {
    private val app = context.applicationContext
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var closed = false
    private val echo = ClipboardEcho()
    /** Where [X11Clipboard.own] runs: the session's input thread, so a following Ctrl+V is ordered after it. */
    @Volatile internal var post: ((() -> Unit) -> Unit)? = null

    init {
        x.onGuestCopied = ::onGuestCopied
    }

    /**
     * Main thread. Owns CLIPBOARD with the Android clip when it changed since the last sync.
     * [explicit]: the user tapped Paste; otherwise (focus gain, clip change) clips Android marks
     * sensitive (passwords, one-time codes; API 33+) stay on the phone.
     */
    fun pushAndroidClipboard(explicit: Boolean = false) {
        if (closed || !x.available) return
        val text = try {
            val clip = app.getSystemService(ClipboardManager::class.java)?.primaryClip
            if (!explicit && clip != null && isSensitive(clip.description)) {
                DesktopLog.d("clipboard: sensitive Android clip not synced")
                return
            }
            clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString()
        } catch (e: Exception) {
            null
        }
        if (text.isNullOrEmpty()) {
            DesktopLog.d("clipboard: no Android text to sync")
            return
        }
        if (text.length > MAX_TEXT_CHARS || text.toByteArray(Charsets.UTF_8).size > X11Clipboard.MAX_OWN_BYTES) {
            DesktopLog.i("clipboard: Android clip too large to sync")
            return
        }
        if (!echo.changed(text)) return
        DesktopLog.d("clipboard: android->guest len=${text.length}")
        val own = { if (!closed) x.own(text) }
        val poster = post
        if (poster != null) poster { own() } else x.runOnWorker { own() }
    }

    /** From `desktop-clipboard`: Chrome copied [text]. */
    internal fun onGuestCopied(text: String) {
        if (closed) return
        if (!echo.changed(text)) return
        DesktopLog.d("clipboard: guest->android len=${text.length}")
        main.post {
            try {
                app.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Chrome", text))
            } catch (e: Exception) {
                DesktopLog.w("clipboard: could not set the Android clip", e)
            }
        }
    }

    /**
     * The bridge owned CLIPBOARD for a long IME commit: the guest no longer holds the last synced
     * text, so the next [pushAndroidClipboard] owns the Android clip again instead of skipping it.
     */
    internal fun noteBridgeOwned() = echo.forget()

    /** Without XFixes, Copy/Cut read the guest clipboard [READ_AFTER_COPY_MS] later. */
    fun scheduleReadAfterCopy() {
        if (!closed) x.readLater(READ_AFTER_COPY_MS)
    }

    /** Closes the X side; Android listeners are the activities' ([watch]). */
    override fun close() {
        closed = true
        x.close()
    }

    companion object {
        const val READ_AFTER_COPY_MS = 250L
        private const val MAX_TEXT_CHARS = X11Clipboard.MAX_OWN_BYTES

        private fun isSensitive(description: ClipDescription?): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true

        /** One PrimaryClipChangedListener that forwards to whichever session is current. Main thread. */
        fun watch(context: Context, current: () -> ClipboardSync?): AutoCloseable {
            val cm = context.getSystemService(ClipboardManager::class.java)
            val listener = ClipboardManager.OnPrimaryClipChangedListener { current()?.pushAndroidClipboard() }
            cm?.addPrimaryClipChangedListener(listener)
            return AutoCloseable { cm?.removePrimaryClipChangedListener(listener) }
        }
    }
}

/**
 * [ClipboardSync]'s echo suppression, pure for tests: one hash of the text both sides last held.
 * [forget] when something else (the bridge's paste) changed the guest side.
 */
internal class ClipboardEcho {
    @Volatile private var lastHash: Int? = null

    /** True (and remembered) when [text] differs from the last synced text. */
    @Synchronized
    fun changed(text: String): Boolean {
        val hash = text.hashCode()
        if (hash == lastHash) return false
        lastHash = hash
        return true
    }

    fun forget() { lastHash = null }
}
