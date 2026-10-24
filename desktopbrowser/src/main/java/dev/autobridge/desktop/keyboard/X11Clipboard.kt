package dev.autobridge.desktop.keyboard

import dev.autobridge.desktop.DesktopLog
import dev.autobridge.desktop.display.X11Connection
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * The guest's CLIPBOARD selection, owned and read through an unmapped InputOnly window.
 *
 * - Owner side (Chrome pastes what we own): `SelectionRequest` is served on the x11-reader
 *   thread with reply-less requests only (ChangeProperty + SendEvent), never waiting.
 * - Reader side (Chrome copied): XFixes reports owner changes; the text is fetched on a
 *   `desktop-clipboard` thread (ConvertSelection, wait ≤ 1 s for SelectionNotify, GetProperty)
 *   and handed to [onGuestCopied]. No INCR: anything larger than one request is skipped.
 *
 * Construction does round trips (atoms, window, XFixes); it throws when the X side cannot be set
 * up, and the session then runs without a clipboard.
 */
class X11Clipboard(private val x: X11Connection) : AutoCloseable {
    internal val atoms = X11ClipboardCodec.Atoms(
        clipboard = x.internAtom("CLIPBOARD"),
        targets = x.internAtom("TARGETS"),
        utf8 = x.internAtom("UTF8_STRING"),
        string = X11Connection.ATOM_STRING,
        text = x.internAtom("TEXT"),
        textPlain = x.internAtom("text/plain;charset=utf-8"),
        incr = x.internAtom("INCR"),
        property = x.internAtom("AUTOBRIDGE_SEL"),
    )
    val window: Int = x.createInputOnlyWindow(X11Connection.PROPERTY_CHANGE_MASK)
    /** XFixes owner notifications are on; without them Copy schedules a timed read instead. */
    val xfixes: Boolean = try {
        x.xfixesSelectSelectionInput(window, atoms.clipboard)
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        false
    }

    @Volatile var available = true
        private set
    /** Most UTF-8 bytes [own] accepts: one ChangeProperty, capped at 200 KB. */
    val maxOwnBytes: Int = minOf(MAX_OWN_BYTES, x.setup.maxRequestLength * 4 - 64)

    @Volatile private var owned: String? = null
    @Volatile private var ownedUtf8: ByteArray? = null
    @Volatile internal var onGuestCopied: ((String) -> Unit)? = null
    private val pendingNotify = AtomicReference<CompletableFuture<Int>?>(null)
    private val restore = AtomicReference<Restore?>(null)
    /** Another client's CLIPBOARD text, read after its last XFixes owner change. */
    @Volatile private var lastGuestCopy: String? = null
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "desktop-clipboard").apply { isDaemon = true } }

    /** Register with [X11Connection.addEventListener]; runs on the reader thread. */
    val eventListener: (Int, ByteArray) -> Unit = { code, event -> onEvent(code, event) }

    init {
        if (!xfixes) DesktopLog.w("clipboard: no XFixes; Chrome copies are read only after Copy")
    }

    /**
     * Makes [text] the CLIPBOARD contents (input thread: one round trip to confirm). False when it
     * is too large or another client kept ownership.
     */
    fun own(text: String): Boolean {
        if (!available) return false
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxOwnBytes) {
            DesktopLog.i("clipboard: ${bytes.size} bytes is too large to own")
            return false
        }
        x.setSelectionOwner(window, atoms.clipboard)
        // The confirm reply comes after any SelectionClear for an older ownership, so the text is
        // stored only once those are handled.
        if (x.getSelectionOwner(atoms.clipboard) != window) return false
        ownedUtf8 = bytes
        owned = text
        return true
    }

    /** Blocking read of the guest CLIPBOARD; never call on the x11-reader thread. Null on any failure. */
    fun read(timeoutMs: Long = READ_TIMEOUT_MS): String? {
        if (!available) return null
        owned?.let { return it }
        var property = convert(atoms.utf8, timeoutMs) ?: return null
        if (property == 0) property = convert(atoms.string, timeoutMs) ?: return null
        if (property == 0) return null
        val reply = x.getProperty(window, property, 0, delete = true, maxBytes = MAX_READ_BYTES)
        if (reply.bytesAfter > 0) runCatching { x.deleteProperty(window, property) }
        return X11ClipboardCodec.decode(reply, atoms)
    }

    /**
     * The bridge just owned [pasted] for its own paste (a long IME commit). [previous] is owned
     * again through [post] (the input thread), so CLIPBOARD keeps what the user copied: once
     * Chrome has fetched the text and then sent no clipboard request for [RESTORE_QUIET_MS]
     * (on device Chrome fetches the text twice per paste, ~150 ms apart, among many TARGETS
     * requests), or [RESTORE_FALLBACK_MS] after the paste if it never fetched, at the latest
     * [RESTORE_MAX_MS]. Skipped when something else took CLIPBOARD in between (a copy in Chrome,
     * an Android push).
     */
    internal fun restoreAfterPaste(pasted: String, previous: String, post: (() -> Unit) -> Unit) {
        if (!available) return
        val r = Restore(pasted, previous, post)
        restore.set(r)
        runCatching {
            worker.schedule({ if (!r.fetched) giveBack(r) }, RESTORE_FALLBACK_MS, TimeUnit.MILLISECONDS)
            worker.schedule({ giveBack(r) }, RESTORE_MAX_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** Reader thread, after serving [request]: (re)starts the quiet timer of a pending restore. */
    private fun noteServed(request: X11ClipboardCodec.SelectionRequest, response: X11ClipboardCodec.Response) {
        val r = restore.get() ?: return
        if (!r.fetched && !X11ClipboardCodec.isTextFetch(request, response, atoms)) return
        r.fetched = true
        r.quiet?.cancel(false)
        r.quiet = runCatching { worker.schedule({ giveBack(r) }, RESTORE_QUIET_MS, TimeUnit.MILLISECONDS) }.getOrNull()
    }

    /** Input thread, before a bridge paste: what a pending restore would give back, taken over. */
    internal fun takePendingRestore(): String? = restore.getAndSet(null)?.previous

    /**
     * The CLIPBOARD text as known without a round trip: ours, or what XFixes last reported Chrome
     * copied. Null when unknown (no XFixes, no owner, the last read failed).
     */
    internal fun knownText(): String? = owned ?: lastGuestCopy.takeIf { xfixes }

    private fun giveBack(r: Restore) {
        r.post {
            if (restore.compareAndSet(r, null) && available && owned == r.pasted) {
                try {
                    own(r.previous)
                } catch (e: Exception) {
                    DesktopLog.d("clipboard: restore failed: ${DesktopLog.describe(e)}")
                }
            }
        }
    }

    /** [read] on the `desktop-clipboard` thread; [callback] runs there too. */
    fun readAsync(callback: (String?) -> Unit) {
        if (!available) { callback(null); return }
        runCatching { worker.execute { callback(safeRead()) } }.onFailure { callback(null) }
    }

    /** Runs [block] on the `desktop-clipboard` thread (round trips off the caller's thread). */
    internal fun runOnWorker(block: () -> Unit) {
        if (!available) return
        runCatching {
            worker.execute {
                try { block() } catch (e: Exception) { DesktopLog.i("clipboard: ${DesktopLog.describe(e)}") }
            }
        }
    }

    /** Without XFixes: reads after a Copy/Cut, giving Chrome [delayMs] to take the selection. */
    fun readLater(delayMs: Long) {
        if (!available) return
        runCatching { worker.schedule({ forward() }, delayMs, TimeUnit.MILLISECONDS) }
    }

    override fun close() {
        available = false
        owned = null
        ownedUtf8 = null
        lastGuestCopy = null
        restore.set(null)
        x.removeEventListener(eventListener)
        pendingNotify.getAndSet(null)?.completeExceptionally(IOException("clipboard closed"))
        worker.shutdownNow()
    }

    private fun onEvent(code: Int, event: ByteArray) {
        if (!available) return
        when {
            code == X11Connection.SELECTION_REQUEST -> serve(X11ClipboardCodec.parseSelectionRequest(event))
            code == X11Connection.SELECTION_CLEAR -> {
                val clear = X11ClipboardCodec.parseSelectionClear(event)
                if (clear.selection == atoms.clipboard && clear.owner == window) { owned = null; ownedUtf8 = null }
            }
            code == X11Connection.SELECTION_NOTIFY -> {
                val notify = X11ClipboardCodec.parseSelectionNotify(event)
                if (notify.requestor == window && notify.selection == atoms.clipboard) {
                    pendingNotify.getAndSet(null)?.complete(notify.property)
                }
            }
            x.isXfixesSelectionNotify(code) -> {
                val change = X11ClipboardCodec.parseOwnerChange(event)
                if (change.selection == atoms.clipboard && change.owner != window) {
                    lastGuestCopy = null
                    if (change.owner != 0) runCatching { worker.execute { forward() } }
                }
            }
        }
    }

    /** Reader thread: reply-less requests only. */
    private fun serve(request: X11ClipboardCodec.SelectionRequest) {
        if (request.selection != atoms.clipboard) return
        val response = X11ClipboardCodec.respond(request, owned, ownedUtf8, atoms)
        try {
            if (response.property != 0) {
                x.changeProperty(request.requestor, response.property, response.type, response.format, response.data)
            }
            x.sendSelectionNotify(request.requestor, request.selection, request.target, response.property, request.time)
        } catch (e: Exception) {
            DesktopLog.d("clipboard: serving a paste failed: ${DesktopLog.describe(e)}")
        }
        noteServed(request, response)
    }

    private fun forward() {
        val text = safeRead() ?: return
        if (text == owned) return
        lastGuestCopy = text
        onGuestCopied?.invoke(text)
    }

    private fun safeRead(): String? = try {
        read()
    } catch (e: Exception) {
        DesktopLog.i("clipboard: read failed: ${DesktopLog.describe(e)}")
        null
    }

    /** ConvertSelection and wait for the SelectionNotify: the property (0 = refused), or null on timeout. */
    private fun convert(target: Int, timeoutMs: Long): Int? {
        val future = CompletableFuture<Int>()
        pendingNotify.getAndSet(future)?.cancel(false)
        x.convertSelection(window, atoms.clipboard, target, atoms.property)
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            pendingNotify.compareAndSet(future, null)
            DesktopLog.i("clipboard: no answer from the owner")
            null
        } catch (e: ExecutionException) {
            null
        } catch (e: CancellationException) {
            null
        }
    }

    /** A bridge paste that took CLIPBOARD from [previous]. */
    private class Restore(val pasted: String, val previous: String, val post: (() -> Unit) -> Unit) {
        @Volatile var fetched = false
        @Volatile var quiet: ScheduledFuture<*>? = null
    }

    companion object {
        const val MAX_OWN_BYTES = 200 * 1024
        const val MAX_READ_BYTES = 1024 * 1024
        const val READ_TIMEOUT_MS = 1000L
        /** A bridge paste gives CLIPBOARD back this long after its Ctrl+V if Chrome never fetched it. */
        const val RESTORE_FALLBACK_MS = 2000L
        /** ...or this long after Chrome's last clipboard request once it fetched the text. */
        const val RESTORE_QUIET_MS = 600L
        /** ...and never later than this after the paste. */
        const val RESTORE_MAX_MS = 5000L
    }
}

/** Byte-level parts of [X11Clipboard], pure for tests. */
internal object X11ClipboardCodec {
    data class Atoms(
        val clipboard: Int,
        val targets: Int,
        val utf8: Int,
        val string: Int,
        val text: Int,
        val textPlain: Int,
        val incr: Int,
        val property: Int,
        val atom: Int = X11Connection.ATOM_ATOM,
    )

    data class SelectionRequest(val time: Int, val owner: Int, val requestor: Int, val selection: Int, val target: Int, val property: Int)
    data class SelectionNotify(val time: Int, val requestor: Int, val selection: Int, val target: Int, val property: Int)
    data class SelectionClear(val time: Int, val owner: Int, val selection: Int)
    data class OwnerChange(val window: Int, val owner: Int, val selection: Int)

    /** What to write for a request: [property] 0 refuses it (no ChangeProperty). */
    class Response(val property: Int, val type: Int, val format: Int, val data: ByteArray)

    fun parseSelectionRequest(e: ByteArray) = SelectionRequest(int(e, 4), int(e, 8), int(e, 12), int(e, 16), int(e, 20), int(e, 24))
    fun parseSelectionNotify(e: ByteArray) = SelectionNotify(int(e, 4), int(e, 8), int(e, 12), int(e, 16), int(e, 20))
    fun parseSelectionClear(e: ByteArray) = SelectionClear(int(e, 4), int(e, 8), int(e, 12))
    fun parseOwnerChange(e: ByteArray) = OwnerChange(int(e, 4), int(e, 8), int(e, 12))

    fun respond(request: SelectionRequest, text: String?, utf8: ByteArray?, atoms: Atoms): Response {
        val refuse = Response(0, 0, 8, ByteArray(0))
        if (text == null) return refuse
        val property = if (request.property == 0) request.target else request.property
        return when (request.target) {
            atoms.targets -> Response(property, atoms.atom, 32,
                ints(intArrayOf(atoms.targets, atoms.utf8, atoms.string, atoms.text, atoms.textPlain)))
            atoms.utf8, atoms.text -> Response(property, atoms.utf8, 8, utf8 ?: text.toByteArray(Charsets.UTF_8))
            atoms.textPlain -> Response(property, atoms.textPlain, 8, utf8 ?: text.toByteArray(Charsets.UTF_8))
            atoms.string -> Response(property, atoms.string, 8, latin1(text))
            else -> refuse
        }
    }

    /** A request that fetched the text itself (not TARGETS, not refused): a paste was served. */
    fun isTextFetch(request: SelectionRequest, response: Response, atoms: Atoms): Boolean =
        request.selection == atoms.clipboard && response.property != 0 && request.target != atoms.targets

    /** STRING is Latin-1: one byte per code point, `?` for anything outside it. */
    fun latin1(text: String): ByteArray {
        val out = ByteArray(text.codePointCount(0, text.length))
        var i = 0
        var n = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            out[n++] = if (cp <= 0xff) cp.toByte() else '?'.code.toByte()
            i += Character.charCount(cp)
        }
        return out
    }

    /** Text of a GetProperty reply; null for INCR, truncated, non-8-bit or non-text replies. */
    fun decode(reply: X11Connection.PropertyReply, atoms: Atoms): String? {
        if (reply.type == atoms.incr) {
            DesktopLog.i("clipboard: INCR transfer skipped")
            return null
        }
        if (reply.bytesAfter > 0) {
            DesktopLog.i("clipboard: over ${X11Clipboard.MAX_READ_BYTES} bytes, skipped")
            return null
        }
        if (reply.format != 8) return null
        return when (reply.type) {
            atoms.utf8, atoms.textPlain, atoms.text -> String(reply.data, Charsets.UTF_8)
            atoms.string -> String(reply.data, Charsets.ISO_8859_1)
            else -> null
        }
    }

    fun ints(values: IntArray): ByteArray {
        val out = ByteArray(values.size * 4)
        for ((i, v) in values.withIndex()) {
            out[i * 4] = v.toByte()
            out[i * 4 + 1] = (v shr 8).toByte()
            out[i * 4 + 2] = (v shr 16).toByte()
            out[i * 4 + 3] = (v shr 24).toByte()
        }
        return out
    }

    private fun int(e: ByteArray, at: Int): Int =
        (e[at].toInt() and 0xff) or ((e[at + 1].toInt() and 0xff) shl 8) or
            ((e[at + 2].toInt() and 0xff) shl 16) or ((e[at + 3].toInt() and 0xff) shl 24)
}
