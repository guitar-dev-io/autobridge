package dev.autobridge.desktop.display

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.DataInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The new request encodings, against a scripted little-endian server on piped streams. */
class X11ConnectionRequestsTest {
    private val server = FakeServer()
    private val x = server.connect()

    @After fun close() {
        x.close()
        server.stop()
    }

    private fun le(size: Int, fill: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array()

    private fun reply(extraBytes: Int = 0, fill: ByteBuffer.() -> Unit = {}): (ByteArray) -> ByteArray = {
        le(32 + extraBytes) { put(1); put(0); putShort(0); putInt(extraBytes / 4); fill() }
    }

    @Test fun handshakeParsesMaxRequestLength() {
        assertEquals(4096, x.setup.maxRequestLength)
        assertEquals(ROOT, x.setup.root)
        assertEquals(8, x.setup.minKeycode)
    }

    @Test fun internAtomIsEncodedAndCached() {
        server.replies[16] = reply { position(8); putInt(0x45) }
        assertEquals(0x45, x.internAtom("CLIPBOARD"))
        val expected = le(20) { put(16); put(0); putShort(5); putShort(9); putShort(0); put("CLIPBOARD".toByteArray()) }
        assertArrayEquals(expected, server.next())
        assertEquals(0x45, x.internAtom("CLIPBOARD"))
        x.sync()
        assertEquals(43, server.next()[0].toInt()) // no second InternAtom
        server.replies[16] = reply { position(8); putInt(0) }
        assertEquals(0, x.internAtom("NOPE", onlyIfExists = true))
        assertEquals(1, server.next()[1].toInt())
    }

    @Test fun createInputOnlyWindow() {
        val id = x.createInputOnlyWindow(X11Connection.PROPERTY_CHANGE_MASK)
        assertEquals(ID_BASE, id)
        val expected = le(36) {
            put(1); put(0); putShort(9); putInt(ID_BASE); putInt(ROOT)
            putShort(0); putShort(0); putShort(1); putShort(1); putShort(0); putShort(2)
            putInt(0); putInt(0x800); putInt(0x400000)
        }
        assertArrayEquals(expected, server.next())
    }

    @Test fun changeAndDeleteProperty() {
        x.changeProperty(0x200001, 0x300, 0x103, 8, "abcde".toByteArray())
        assertArrayEquals(le(32) {
            put(18); put(0); putShort(8); putInt(0x200001); putInt(0x300); putInt(0x103)
            put(8); put(0); put(0); put(0); putInt(5); put("abcde".toByteArray())
        }, server.next())
        x.changeProperty(0x200001, 0x300, 4, 32, le(8) { putInt(0x101); putInt(0x102) })
        assertArrayEquals(le(32) {
            put(18); put(0); putShort(8); putInt(0x200001); putInt(0x300); putInt(4)
            put(32); put(0); put(0); put(0); putInt(2); putInt(0x101); putInt(0x102)
        }, server.next())
        x.deleteProperty(0x200001, 0x300)
        assertArrayEquals(le(12) { put(19); put(0); putShort(3); putInt(0x200001); putInt(0x300) }, server.next())
    }

    @Test fun getPropertyRequestAndReply() {
        val data = "héllo".toByteArray(Charsets.UTF_8) // 6 bytes
        server.replies[20] = { le(40) { put(1); put(8); putShort(0); putInt(2); putInt(0x103); putInt(0); putInt(data.size); position(32); put(data) } }
        val r = x.getProperty(0x200001, 0x300, 0, delete = true, maxBytes = 1024 * 1024)
        assertArrayEquals(le(24) {
            put(20); put(1); putShort(6); putInt(0x200001); putInt(0x300); putInt(0); putInt(0); putInt(262144)
        }, server.next())
        assertEquals(0x103, r.type)
        assertEquals(8, r.format)
        assertEquals(0, r.bytesAfter)
        assertEquals("héllo", String(r.data, Charsets.UTF_8))
    }

    @Test fun selectionRequests() {
        x.setSelectionOwner(0x200001, 0x101)
        assertArrayEquals(le(16) { put(22); put(0); putShort(4); putInt(0x200001); putInt(0x101); putInt(0) }, server.next())
        server.replies[23] = reply { position(8); putInt(0x600010) }
        assertEquals(0x600010, x.getSelectionOwner(0x101))
        assertArrayEquals(le(8) { put(23); put(0); putShort(2); putInt(0x101) }, server.next())
        x.convertSelection(0x200001, 0x101, 0x103, 0x107)
        assertArrayEquals(le(24) { put(24); put(0); putShort(6); putInt(0x200001); putInt(0x101); putInt(0x103); putInt(0x107); putInt(0) }, server.next())
        x.sendSelectionNotify(0x400003, 0x101, 0x103, 0x300, 77)
        assertArrayEquals(le(44) {
            put(25); put(0); putShort(11); putInt(0x400003); putInt(0)
            put(31); put(0); putShort(0); putInt(77); putInt(0x400003); putInt(0x101); putInt(0x103); putInt(0x300)
        }, server.next())
    }

    @Test fun focusRequests() {
        x.setInputFocus(0x400001)
        assertArrayEquals(le(12) { put(42); put(2); putShort(3); putInt(0x400001); putInt(0) }, server.next())
        server.replies[43] = reply { position(1); put(2); position(8); putInt(0x400001) }
        assertEquals(0x400001, x.getInputFocus())
        assertArrayEquals(le(4) { put(43); put(0); putShort(1) }, server.next())
        server.replies[15] = reply(4) { position(8); putInt(ROOT); putInt(0x400000); putShort(1); position(32); putInt(0x400002) }
        assertEquals(0x400000, x.queryTreeParent(0x400001))
        assertArrayEquals(le(8) { put(15); put(0); putShort(2); putInt(0x400001) }, server.next())
    }

    @Test fun modifierMapping() {
        val codes = byteArrayOf(50, 62, 66, 0, 37, 105, 0, 0, 64, 108, 0, 0, (-123).toByte(), (-122).toByte(), 0, 0)
        server.replies[119] = { le(48) { put(1); put(2); putShort(0); putInt(4); position(32); put(codes) } }
        assertArrayEquals(intArrayOf(50, 62, 66, 37, 105, 64, 108, 133, 134), x.getModifierMapping())
        assertArrayEquals(le(4) { put(119); put(0); putShort(1) }, server.next())
    }

    @Test fun xfixesSelectionInput() {
        server.replies[98] = reply { position(8); put(1); put(140.toByte()); put(87); put(140.toByte()) }
        server.replies[140] = reply { position(8); putInt(4); putInt(0) }
        assertTrue(x.xfixesSelectSelectionInput(0x200001, 0x101))
        assertEquals(98, server.next()[0].toInt())
        // QueryVersion negotiates XFixes 4.0 (HideCursor/ShowCursor need 4.0; 2.0 features still work).
        assertArrayEquals(le(12) { put(140.toByte()); put(0); putShort(3); putInt(4); putInt(0) }, server.next())
        assertArrayEquals(le(16) { put(140.toByte()); put(2); putShort(4); putInt(0x200001); putInt(0x101); putInt(1) }, server.next())
        assertTrue(x.isXfixesSelectionNotify(87))
        assertFalse(x.isXfixesSelectionNotify(88))
        // Version is queried once.
        assertTrue(x.xfixesSelectSelectionInput(0x200001, 0x102))
        assertEquals(2, server.next()[1].toInt())
    }

    @Test fun xfixesHideAndShowCursor() {
        server.replies[98] = reply { position(8); put(1); put(140.toByte()); put(87); put(140.toByte()) }
        server.replies[140] = reply { position(8); putInt(4); putInt(0) }
        assertTrue(x.hideCursor())
        assertEquals(98, server.next()[0].toInt()) // QueryExtension XFIXES
        assertArrayEquals(le(12) { put(140.toByte()); put(0); putShort(3); putInt(4); putInt(0) }, server.next()) // QueryVersion 4.0
        assertArrayEquals(le(8) { put(140.toByte()); put(29); putShort(2); putInt(ROOT) }, server.next()) // HideCursor
        assertTrue(x.showCursor())
        assertArrayEquals(le(8) { put(140.toByte()); put(30); putShort(2); putInt(ROOT) }, server.next()) // ShowCursor (version cached)
    }

    @Test fun noXfixesCursorHideShow() {
        server.replies[98] = reply { position(8); put(0) }
        assertFalse(x.hideCursor())
        assertFalse(x.showCursor())
    }

    @Test fun noXfixes() {
        server.replies[98] = reply { position(8); put(0) }
        assertFalse(x.xfixesSelectSelectionInput(0x200001, 0x101))
        assertFalse(x.isXfixesSelectionNotify(87))
    }

    @Test fun batchedKeyboardMappingRun() {
        x.changeKeyboardMappingRun(100, 2, intArrayOf(0x1000e01, 0x1000e01, 0x1000e02, 0x1000e02))
        assertArrayEquals(le(24) {
            put(100); put(2); putShort(6); put(100); put(2); putShort(0)
            putInt(0x1000e01); putInt(0x1000e01); putInt(0x1000e02); putInt(0x1000e02)
        }, server.next())
    }

    @Test fun eventListenersGetEventsAlongsideOnEvent() {
        val got = LinkedBlockingQueue<Pair<Int, Int>>()
        val removed = LinkedBlockingQueue<Int>()
        val onEvent = CountDownLatch(2)
        x.onEvent = { _, _ -> onEvent.countDown() }
        val listener: (Int, ByteArray) -> Unit = { code, e -> got += code to (e[4].toInt() and 0xff) }
        val other: (Int, ByteArray) -> Unit = { code, _ -> removed += code }
        x.addEventListener(listener)
        x.addEventListener(other)
        x.removeEventListener(other)
        server.event(le(32) { put(34); put(0); putShort(0); put(1); put(100); put(2) })
        // From SendEvent: the high bit is set and masked off.
        server.event(le(32) { put((0x80 or 31).toByte()); put(0); putShort(0); putInt(5) })
        assertEquals(34 to 1, got.poll(5, TimeUnit.SECONDS))
        assertEquals(31 to 5, got.poll(5, TimeUnit.SECONDS))
        assertTrue(onEvent.await(5, TimeUnit.SECONDS))
        assertNull(removed.poll(100, TimeUnit.MILLISECONDS))
    }

    @Test fun throwingListenerDoesNotEndTheConnection() {
        x.addEventListener { _, _ -> throw IllegalStateException("boom") }
        server.event(le(32) { put(34) })
        server.replies[43] = reply { position(8); putInt(7) }
        assertEquals(7, x.getInputFocus())
        assertTrue(x.isOpen)
    }

    /** Reads requests, records them and answers the ones with a scripted reply (sequence patched in). */
    private class FakeServer {
        private val toServer = PipedInputStream(1 shl 20)
        private val clientOut = PipedOutputStream(toServer)
        private val clientIn = PipedInputStream(1 shl 20)
        private val serverOut = PipedOutputStream(clientIn)
        private val requests = LinkedBlockingQueue<ByteArray>()
        val replies = ConcurrentHashMap<Int, (ByteArray) -> ByteArray>()
        private val writeLock = Any()
        private var thread: Thread? = null

        fun connect(): X11Connection {
            thread = Thread({ serve() }, "fake-x11").apply { isDaemon = true; start() }
            return X11Connection.open(clientIn, clientOut, Closeable { clientOut.close() })
        }

        fun next(): ByteArray = requests.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no request")

        fun event(bytes: ByteArray) = write(bytes)

        fun stop() {
            runCatching { clientOut.close() }
            thread?.join(2000)
        }

        private fun write(bytes: ByteArray) = synchronized(writeLock) {
            serverOut.write(bytes)
            serverOut.flush()
        }

        private fun serve() {
            val input = DataInputStream(toServer)
            try {
                input.readFully(ByteArray(12))
                write(setupReply())
                var seq = 0
                while (true) {
                    val header = ByteArray(4)
                    input.readFully(header)
                    val length = ((header[2].toInt() and 0xff) or ((header[3].toInt() and 0xff) shl 8)) * 4
                    val request = header.copyOf(length)
                    input.readFully(request, 4, length - 4)
                    seq = (seq + 1) and 0xffff
                    requests += request
                    val opcode = request[0].toInt() and 0xff
                    replies[opcode]?.let { make ->
                        val reply = make(request)
                        reply[2] = seq.toByte()
                        reply[3] = (seq shr 8).toByte()
                        write(reply)
                    }
                    // GetInputFocus doubles as sync(): answer it even without a script.
                    if (opcode == 43 && replies[43] == null) {
                        val r = ByteArray(32)
                        r[0] = 1; r[2] = seq.toByte(); r[3] = (seq shr 8).toByte()
                        write(r)
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { serverOut.close() }
            }
        }

        private fun setupReply(): ByteArray {
            val vendor = "Fake".toByteArray()
            val body = ByteBuffer.allocate(32 + vendor.size + 40).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0, 12101004)
                putInt(4, ID_BASE)
                putInt(8, 0x001fffff)
                putShort(16, vendor.size.toShort())
                putShort(18, 4096.toShort())
                put(20, 1)
                put(21, 0)
                put(26, 8)
                put(27, 255.toByte())
                position(32); put(vendor)
                putInt(36, ROOT)
                putShort(36 + 20, 1600)
                putShort(36 + 22, 1600)
            }.array()
            val prefix = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
                put(1); put(0); putShort(11); putShort(0); putShort((body.size / 4).toShort())
            }.array()
            return prefix + body
        }
    }

    private companion object {
        const val ROOT = 0x123
        const val ID_BASE = 0x00200000
    }
}
