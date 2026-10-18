package dev.autobridge.desktop.display

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * A deliberately small X11 client: just what the bridge needs from the X server.
 *
 * - XTEST `FakeInput` to deliver pointer, wheel and key events as if from real devices;
 * - `GetKeyboardMapping`/`ChangeKeyboardMapping` to type any character (an unmapped one is bound
 *   to a spare keycode first, the way xdotool does);
 * - DAMAGE to learn when the screen changed, so frames are drawn on change instead of polled.
 *
 * Speaks the core protocol over any byte stream: a Unix socket on the phone, TCP in tests.
 * Little-endian only (the byte order this client announces). All writes are serialized; one
 * reader thread dispatches replies by sequence number and events to [onEvent].
 */
class X11Connection private constructor(
    private val input: InputStream,
    private val output: OutputStream,
    private val closeable: Closeable,
) : Closeable {
    val setup: Setup
    private var sequence = 0
    private val pending = ConcurrentHashMap<Int, CompletableFuture<ByteArray>>()
    private val writeLock = Any()
    @Volatile private var closed = false
    @Volatile var onEvent: ((Int, ByteArray) -> Unit)? = null
    @Volatile var onClosed: ((Throwable?) -> Unit)? = null
    /** Errors for requests nobody waits on: (error code, major opcode, minor opcode). */
    @Volatile var onError: ((Int, Int, Int) -> Unit)? = null
    private var nextId = 0
    private val extensions = HashMap<String, Extension>()

    data class Setup(
        val resourceIdBase: Int,
        val resourceIdMask: Int,
        val minKeycode: Int,
        val maxKeycode: Int,
        val root: Int,
        val width: Int,
        val height: Int,
        val vendor: String,
    )

    data class Extension(val majorOpcode: Int, val firstEvent: Int, val firstError: Int)

    init {
        setup = handshake()
        Thread(::readLoop, "x11-reader").apply { isDaemon = true; start() }
    }

    fun allocateId(): Int = synchronized(writeLock) { setup.resourceIdBase or (nextId++ and setup.resourceIdMask) }

    fun queryExtension(name: String): Extension? {
        extensions[name]?.let { return it }
        val nameBytes = name.toByteArray(Charsets.ISO_8859_1)
        val body = buffer(8 + pad4(nameBytes.size)).apply {
            put(OP_QUERY_EXTENSION.toByte()); put(0)
            putShort(((8 + pad4(nameBytes.size)) / 4).toShort())
            putShort(nameBytes.size.toShort()); putShort(0)
            put(nameBytes)
        }
        val reply = request(body.array(), expectReply = true)!!
        if (reply[8].toInt() == 0) return null
        return Extension(reply[9].toInt() and 0xff, reply[10].toInt() and 0xff, reply[11].toInt() and 0xff)
            .also { extensions[name] = it }
    }

    /** XTEST FakeInput. [type] is 2..6 (KeyPress..MotionNotify). Coordinates matter for motion only. */
    fun fakeInput(type: Int, detail: Int, x: Int = 0, y: Int = 0) {
        val xtest = queryExtension("XTEST") ?: throw IOException("X server has no XTEST")
        val body = buffer(36).apply {
            put(xtest.majorOpcode.toByte()); put(XTEST_FAKE_INPUT.toByte()); putShort(9)
            put(type.toByte()); put(detail.toByte()); putShort(0)
            putInt(0) // time: CurrentTime
            putInt(if (type == MOTION_NOTIFY) setup.root else 0)
            putInt(0); putInt(0)
            putShort(x.toShort()); putShort(y.toShort())
            putInt(0); putShort(0); put(0); put(0)
        }
        request(body.array(), expectReply = false)
    }

    /** keysyms[keycode - minKeycode][column]. */
    fun keyboardMapping(): Array<IntArray> {
        val count = setup.maxKeycode - setup.minKeycode + 1
        val body = buffer(8).apply {
            put(OP_GET_KEYBOARD_MAPPING.toByte()); put(0); putShort(2)
            put(setup.minKeycode.toByte()); put(count.toByte()); putShort(0)
        }
        val reply = request(body.array(), expectReply = true)!!
        val perKeycode = reply[1].toInt() and 0xff
        val data = ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN)
        return Array(count) { k -> IntArray(perKeycode) { c -> data.getInt(32 + (k * perKeycode + c) * 4) } }
    }

    fun changeKeyboardMapping(keycode: Int, keysyms: IntArray) {
        val body = buffer(8 + keysyms.size * 4).apply {
            put(OP_CHANGE_KEYBOARD_MAPPING.toByte()); put(1)
            putShort((2 + keysyms.size).toShort())
            put(keycode.toByte()); put(keysyms.size.toByte()); putShort(0)
            keysyms.forEach { putInt(it) }
        }
        request(body.array(), expectReply = false)
    }

    /** Direct children of [window], bottom to top. */
    fun queryTree(window: Int = setup.root): List<Int> {
        val body = buffer(8).apply { put(OP_QUERY_TREE.toByte()); put(0); putShort(2); putInt(window) }
        val reply = ByteBuffer.wrap(request(body.array(), expectReply = true)!!).order(ByteOrder.LITTLE_ENDIAN)
        val count = reply.getShort(16).toInt() and 0xffff
        return List(count) { reply.getInt(32 + it * 4) }
    }

    data class WindowInfo(val id: Int, val viewable: Boolean, val overrideRedirect: Boolean, val x: Int, val y: Int, val width: Int, val height: Int)

    /** Map state, override-redirect and geometry of [window] (two round trips). */
    fun windowInfo(window: Int): WindowInfo {
        val attrs = buffer(8).apply { put(OP_GET_WINDOW_ATTRIBUTES.toByte()); put(0); putShort(2); putInt(window) }
        val a = request(attrs.array(), expectReply = true)!!
        val geometry = buffer(8).apply { put(OP_GET_GEOMETRY.toByte()); put(0); putShort(2); putInt(window) }
        val g = ByteBuffer.wrap(request(geometry.array(), expectReply = true)!!).order(ByteOrder.LITTLE_ENDIAN)
        return WindowInfo(
            id = window,
            viewable = (a[26].toInt() and 0xff) == MAP_STATE_VIEWABLE,
            overrideRedirect = a[27].toInt() != 0,
            x = g.getShort(12).toInt(),
            y = g.getShort(14).toInt(),
            width = g.getShort(16).toInt() and 0xffff,
            height = g.getShort(18).toInt() and 0xffff,
        )
    }

    /** Moves and resizes [window]. With no window manager running, the server applies it directly. */
    fun configureWindow(window: Int, x: Int, y: Int, width: Int, height: Int) {
        val body = buffer(28).apply {
            put(OP_CONFIGURE_WINDOW.toByte()); put(0); putShort(7)
            putInt(window)
            putShort((CW_X or CW_Y or CW_WIDTH or CW_HEIGHT).toShort()); putShort(0)
            putInt(x); putInt(y); putInt(width); putInt(height)
        }
        request(body.array(), expectReply = false)
    }

    /** Pointer position on the root window (diagnostics, and proof that input reached the server). */
    fun queryPointer(): Pair<Int, Int> {
        val body = buffer(8).apply { put(OP_QUERY_POINTER.toByte()); put(0); putShort(2); putInt(setup.root) }
        val reply = ByteBuffer.wrap(request(body.array(), expectReply = true)!!).order(ByteOrder.LITTLE_ENDIAN)
        return reply.getShort(16).toInt() to reply.getShort(18).toInt()
    }

    /** Round trip: everything sent before has been processed by the server when this returns. */
    fun sync() {
        val body = buffer(4).apply { put(OP_GET_INPUT_FOCUS.toByte()); put(0); putShort(1) }
        request(body.array(), expectReply = true)
    }

    /** DAMAGE on the root window at NonEmpty level; returns the damage id, or null without DAMAGE. */
    fun createRootDamage(): Int? {
        val damage = queryExtension("DAMAGE") ?: return null
        val version = buffer(12).apply {
            put(damage.majorOpcode.toByte()); put(DAMAGE_QUERY_VERSION.toByte()); putShort(3)
            putInt(1); putInt(1)
        }
        request(version.array(), expectReply = true)
        val id = allocateId()
        val create = buffer(16).apply {
            put(damage.majorOpcode.toByte()); put(DAMAGE_CREATE.toByte()); putShort(4)
            putInt(id); putInt(setup.root); put(DAMAGE_LEVEL_NON_EMPTY.toByte()); put(0); put(0); put(0)
        }
        request(create.array(), expectReply = false)
        return id
    }

    /** Clears the damage region so the next change raises a new DamageNotify. */
    fun subtractDamage(damageId: Int) {
        val damage = extensions["DAMAGE"] ?: return
        val body = buffer(16).apply {
            put(damage.majorOpcode.toByte()); put(DAMAGE_SUBTRACT.toByte()); putShort(4)
            putInt(damageId); putInt(0); putInt(0)
        }
        request(body.array(), expectReply = false)
    }

    fun isDamageEvent(code: Int): Boolean = extensions["DAMAGE"]?.let { code == it.firstEvent } ?: false

    override fun close() {
        closed = true
        runCatching { closeable.close() }
        pending.values.forEach { it.completeExceptionally(IOException("X connection closed")) }
        pending.clear()
    }

    val isOpen: Boolean get() = !closed

    private fun request(bytes: ByteArray, expectReply: Boolean): ByteArray? {
        val future: CompletableFuture<ByteArray>?
        synchronized(writeLock) {
            if (closed) throw IOException("X connection closed")
            sequence = (sequence + 1) and 0xffff
            future = if (expectReply) CompletableFuture<ByteArray>().also { pending[sequence] = it } else null
            output.write(bytes)
            output.flush()
        }
        return future?.get(REPLY_TIMEOUT_S, TimeUnit.SECONDS)
    }

    private fun readLoop() {
        val data = DataInputStream(input)
        var failure: Throwable? = null
        try {
            val header = ByteArray(32)
            while (!closed) {
                data.readFully(header)
                val kind = header[0].toInt() and 0x7f
                val seq = (header[2].toInt() and 0xff) or ((header[3].toInt() and 0xff) shl 8)
                when (kind) {
                    0 -> {
                        val code = header[1].toInt() and 0xff
                        val major = header[10].toInt() and 0xff
                        val minor = (header[8].toInt() and 0xff) or ((header[9].toInt() and 0xff) shl 8)
                        val waiter = pending.remove(seq)
                        if (waiter != null) waiter.completeExceptionally(IOException("X error $code (major $major minor $minor)"))
                        else onError?.invoke(code, major, minor)
                    }
                    1 -> {
                        val extra = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int * 4
                        val reply = header.copyOf(32 + extra)
                        if (extra > 0) data.readFully(reply, 32, extra)
                        pending.remove(seq)?.complete(reply)
                    }
                    GENERIC_EVENT -> {
                        val extra = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int * 4
                        if (extra > 0) data.skipBytes(extra)
                    }
                    else -> onEvent?.invoke(kind, header.copyOf())
                }
            }
        } catch (e: Throwable) {
            failure = e
        } finally {
            closed = true
            pending.values.forEach { it.completeExceptionally(IOException("X connection lost", failure)) }
            pending.clear()
            onClosed?.invoke(failure)
        }
    }

    private fun handshake(): Setup {
        output.write(byteArrayOf(0x6c, 0, 11, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        output.flush()
        val data = DataInputStream(input)
        val prefix = ByteArray(8)
        data.readFully(prefix)
        val extra = ((prefix[6].toInt() and 0xff) or ((prefix[7].toInt() and 0xff) shl 8)) * 4
        val body = ByteArray(extra)
        data.readFully(body)
        if (prefix[0].toInt() != 1) {
            val reason = String(body, 0, minOf(prefix[1].toInt() and 0xff, body.size), Charsets.ISO_8859_1)
            throw IOException("X server refused connection: $reason")
        }
        val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        val idBase = b.getInt(4)
        val idMask = b.getInt(8)
        val vendorLength = b.getShort(16).toInt() and 0xffff
        val formats = b.get(21).toInt() and 0xff
        val minKeycode = b.get(26).toInt() and 0xff
        val maxKeycode = b.get(27).toInt() and 0xff
        val vendor = String(body, 32, vendorLength, Charsets.ISO_8859_1)
        val screen = 32 + pad4(vendorLength) + formats * 8
        return Setup(
            resourceIdBase = idBase,
            resourceIdMask = idMask,
            minKeycode = minKeycode,
            maxKeycode = maxKeycode,
            root = b.getInt(screen),
            width = b.getShort(screen + 20).toInt() and 0xffff,
            height = b.getShort(screen + 22).toInt() and 0xffff,
            vendor = vendor,
        )
    }

    companion object {
        const val KEY_PRESS = 2
        const val KEY_RELEASE = 3
        const val BUTTON_PRESS = 4
        const val BUTTON_RELEASE = 5
        const val MOTION_NOTIFY = 6

        private const val OP_GET_WINDOW_ATTRIBUTES = 3
        private const val OP_CONFIGURE_WINDOW = 12
        private const val OP_GET_GEOMETRY = 14
        private const val OP_QUERY_TREE = 15
        private const val OP_QUERY_POINTER = 38
        private const val MAP_STATE_VIEWABLE = 2
        private const val CW_X = 1
        private const val CW_Y = 2
        private const val CW_WIDTH = 4
        private const val CW_HEIGHT = 8
        private const val OP_GET_INPUT_FOCUS = 43
        private const val OP_QUERY_EXTENSION = 98
        private const val OP_CHANGE_KEYBOARD_MAPPING = 100
        private const val OP_GET_KEYBOARD_MAPPING = 101
        private const val XTEST_FAKE_INPUT = 2
        private const val DAMAGE_QUERY_VERSION = 0
        private const val DAMAGE_CREATE = 1
        private const val DAMAGE_SUBTRACT = 3
        private const val DAMAGE_LEVEL_NON_EMPTY = 3
        private const val GENERIC_EVENT = 35
        private const val REPLY_TIMEOUT_S = 5L

        fun open(input: InputStream, output: OutputStream, closeable: Closeable): X11Connection =
            X11Connection(input, output, closeable)

        private fun pad4(n: Int) = (n + 3) and 3.inv()
        private fun buffer(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    }
}
