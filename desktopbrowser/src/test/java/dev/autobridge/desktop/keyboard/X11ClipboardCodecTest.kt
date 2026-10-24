package dev.autobridge.desktop.keyboard

import dev.autobridge.desktop.display.X11Connection
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class X11ClipboardCodecTest {
    private val atoms = X11ClipboardCodec.Atoms(
        clipboard = 0x101, targets = 0x102, utf8 = 0x103, string = 31, text = 0x104,
        textPlain = 0x105, incr = 0x106, property = 0x107,
    )

    private fun le(size: Int, fill: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array()

    private fun request(target: Int, property: Int) =
        X11ClipboardCodec.SelectionRequest(time = 77, owner = 0x200001, requestor = 0x400003, selection = atoms.clipboard, target = target, property = property)

    @Test fun parsesSelectionRequest() {
        val event = le(32) {
            put(30); put(0); putShort(5)
            putInt(1234); putInt(0x200001); putInt(0x400003); putInt(atoms.clipboard); putInt(atoms.utf8); putInt(0x300)
        }
        assertEquals(
            X11ClipboardCodec.SelectionRequest(1234, 0x200001, 0x400003, atoms.clipboard, atoms.utf8, 0x300),
            X11ClipboardCodec.parseSelectionRequest(event),
        )
    }

    /** A bridge paste gives CLIPBOARD back only after Chrome fetched the text, not after TARGETS. */
    @Test fun onlyServedTextFetchesCountAsAPaste() {
        fun fetch(target: Int, text: String? = "T") = request(target, atoms.property).let {
            X11ClipboardCodec.isTextFetch(it, X11ClipboardCodec.respond(it, text, null, atoms), atoms)
        }
        assertEquals(false, fetch(atoms.targets))
        assertEquals(true, fetch(atoms.utf8))
        assertEquals(true, fetch(atoms.textPlain))
        assertEquals(true, fetch(atoms.string))
        assertEquals(false, fetch(0x999)) // refused target
        assertEquals(false, fetch(atoms.utf8, text = null)) // nothing owned
    }

    @Test fun parsesSelectionNotifyClearAndOwnerChange() {
        val notify = le(32) { put(31); put(0); putShort(0); putInt(9); putInt(0x200001); putInt(atoms.clipboard); putInt(atoms.utf8); putInt(atoms.property) }
        assertEquals(X11ClipboardCodec.SelectionNotify(9, 0x200001, atoms.clipboard, atoms.utf8, atoms.property), X11ClipboardCodec.parseSelectionNotify(notify))
        val clear = le(32) { put(29); put(0); putShort(0); putInt(9); putInt(0x200001); putInt(atoms.clipboard) }
        assertEquals(X11ClipboardCodec.SelectionClear(9, 0x200001, atoms.clipboard), X11ClipboardCodec.parseSelectionClear(clear))
        val owner = le(32) { put(87); put(0); putShort(0); putInt(0x200001); putInt(0x600010); putInt(atoms.clipboard) }
        assertEquals(X11ClipboardCodec.OwnerChange(0x200001, 0x600010, atoms.clipboard), X11ClipboardCodec.parseOwnerChange(owner))
    }

    @Test fun targetsAreAnAtomList() {
        val r = X11ClipboardCodec.respond(request(atoms.targets, 0x300), "hi", null, atoms)
        assertEquals(0x300, r.property)
        assertEquals(4, r.type)
        assertEquals(32, r.format)
        val expected = le(20) { putInt(atoms.targets); putInt(atoms.utf8); putInt(31); putInt(atoms.text); putInt(atoms.textPlain) }
        assertArrayEquals(expected, r.data)
    }

    @Test fun utf8TargetsGetUtf8AndNonePropertyUsesTheTarget() {
        val text = "ทดสอบ ok"
        val r = X11ClipboardCodec.respond(request(atoms.utf8, 0), text, text.toByteArray(Charsets.UTF_8), atoms)
        assertEquals(atoms.utf8, r.property)
        assertEquals(atoms.utf8, r.type)
        assertEquals(8, r.format)
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), r.data)
        assertEquals(atoms.utf8, X11ClipboardCodec.respond(request(atoms.text, 0x300), text, null, atoms).type)
        assertEquals(atoms.textPlain, X11ClipboardCodec.respond(request(atoms.textPlain, 0x300), text, null, atoms).type)
    }

    @Test fun stringIsLatin1WithQuestionMarks() {
        val r = X11ClipboardCodec.respond(request(31, 0x300), "aé ก😀", null, atoms)
        assertEquals(31, r.type)
        assertArrayEquals(byteArrayOf('a'.code.toByte(), 0xe9.toByte(), ' '.code.toByte(), '?'.code.toByte(), '?'.code.toByte()), r.data)
    }

    @Test fun unknownTargetOrNothingOwnedIsRefused() {
        assertEquals(0, X11ClipboardCodec.respond(request(0x999, 0x300), "hi", null, atoms).property)
        assertEquals(0, X11ClipboardCodec.respond(request(atoms.utf8, 0x300), null, null, atoms).property)
    }

    @Test fun selectionNotifyEventEncoding() {
        val event = X11Connection.selectionNotifyEvent(time = 77, requestor = 0x400003, selection = atoms.clipboard, target = atoms.utf8, property = 0x300)
        val expected = le(32) { put(31); put(0); putShort(0); putInt(77); putInt(0x400003); putInt(atoms.clipboard); putInt(atoms.utf8); putInt(0x300) }
        assertArrayEquals(expected, event)
    }

    private fun propertyReply(type: Int, format: Int, bytesAfter: Int, data: ByteArray): ByteArray {
        val padded = (data.size + 3) and 3.inv()
        return le(32 + padded) {
            put(1); put(format.toByte()); putShort(3); putInt(padded / 4)
            putInt(type); putInt(bytesAfter); putInt(if (format == 0) 0 else data.size / (format / 8))
            position(32); put(data)
        }
    }

    @Test fun getPropertyReplyIsParsedAndDecoded() {
        val text = "สวัสดี copy"
        val reply = X11Connection.parsePropertyReply(propertyReply(atoms.utf8, 8, 0, text.toByteArray(Charsets.UTF_8)))
        assertEquals(atoms.utf8, reply.type)
        assertEquals(8, reply.format)
        assertEquals(0, reply.bytesAfter)
        assertEquals(text, X11ClipboardCodec.decode(reply, atoms))

        val latin = X11Connection.parsePropertyReply(propertyReply(31, 8, 0, byteArrayOf('c'.code.toByte(), 0xe9.toByte())))
        assertEquals("cé", X11ClipboardCodec.decode(latin, atoms))
    }

    @Test fun incrTruncatedAndOtherTypesAreSkipped() {
        assertNull(X11ClipboardCodec.decode(X11Connection.parsePropertyReply(propertyReply(atoms.incr, 32, 0, ByteArray(4))), atoms))
        assertNull(X11ClipboardCodec.decode(X11Connection.parsePropertyReply(propertyReply(atoms.utf8, 8, 10, ByteArray(8))), atoms))
        assertNull(X11ClipboardCodec.decode(X11Connection.parsePropertyReply(propertyReply(0x999, 8, 0, ByteArray(4))), atoms))
        val none = X11Connection.parsePropertyReply(propertyReply(0, 0, 0, ByteArray(0)))
        assertEquals(0, none.data.size)
        assertNull(X11ClipboardCodec.decode(none, atoms))
    }
}
