package dev.autobridge.aap

import java.io.ByteArrayOutputStream

/**
 * Just enough protobuf (proto2 wire format) for the Android Auto messages this module sends and
 * reads. The protocol uses a few dozen small messages, so a generated runtime would be most of the
 * module's size for no gain; every message here is written and read field by field.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun uint(field: Int, value: Long): ProtoWriter {
        tag(field, WIRE_VARINT)
        varint(value)
        return this
    }

    fun uint(field: Int, value: Int): ProtoWriter = uint(field, value.toLong() and 0xFFFFFFFFL)

    /** int32/int64: negative values are sign-extended to ten bytes, as protobuf specifies. */
    fun int(field: Int, value: Long): ProtoWriter {
        tag(field, WIRE_VARINT)
        varint(value)
        return this
    }

    fun int(field: Int, value: Int): ProtoWriter = int(field, value.toLong())

    fun bool(field: Int, value: Boolean): ProtoWriter = uint(field, if (value) 1L else 0L)

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, WIRE_LENGTH)
        varint(value.size.toLong())
        out.write(value)
        return this
    }

    fun message(field: Int, value: ProtoWriter): ProtoWriter = bytes(field, value.toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    private fun varint(value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LENGTH = 2
        const val WIRE_FIXED32 = 5
    }
}

/** One decoded field. [varint] is set for varint fields, [bytes] for length-delimited ones. */
class ProtoField(val number: Int, val wire: Int, val varint: Long, val bytes: ByteArray?) {
    val int: Int get() = varint.toInt()
    val bool: Boolean get() = varint != 0L
    val string: String get() = String(bytes ?: ByteArray(0), Charsets.UTF_8)
}

/** Decodes a message into its fields, in wire order. Unknown wire types end the parse with an error. */
object ProtoReader {
    fun fields(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<ProtoField> {
        val result = ArrayList<ProtoField>()
        var pos = offset
        val end = offset + length
        fun readVarint(): Long {
            var shift = 0
            var value = 0L
            while (true) {
                if (pos >= end) throw ProtoException("truncated varint")
                val b = data[pos++].toInt() and 0xFF
                value = value or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
                if (shift > 63) throw ProtoException("varint too long")
            }
        }
        while (pos < end) {
            val key = readVarint()
            val number = (key ushr 3).toInt()
            when (val wire = (key and 7).toInt()) {
                ProtoWriter.WIRE_VARINT -> result += ProtoField(number, wire, readVarint(), null)
                ProtoWriter.WIRE_LENGTH -> {
                    val size = readVarint().toInt()
                    if (size < 0 || pos + size > end) throw ProtoException("field $number overruns the message")
                    result += ProtoField(number, wire, 0, data.copyOfRange(pos, pos + size))
                    pos += size
                }
                ProtoWriter.WIRE_FIXED64 -> {
                    if (pos + 8 > end) throw ProtoException("truncated fixed64")
                    var v = 0L
                    for (i in 0 until 8) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
                    pos += 8
                    result += ProtoField(number, wire, v, null)
                }
                ProtoWriter.WIRE_FIXED32 -> {
                    if (pos + 4 > end) throw ProtoException("truncated fixed32")
                    var v = 0L
                    for (i in 0 until 4) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
                    pos += 4
                    result += ProtoField(number, wire, v, null)
                }
                else -> throw ProtoException("unsupported wire type $wire")
            }
        }
        return result
    }
}

class ProtoException(message: String) : Exception(message)

internal fun List<ProtoField>.first(number: Int): ProtoField? = firstOrNull { it.number == number }
internal fun List<ProtoField>.all(number: Int): List<ProtoField> = filter { it.number == number }

/**
 * A repeated scalar may arrive packed (one length-delimited field) or unpacked (one field per
 * value); a reader has to take both.
 */
internal fun List<ProtoField>.varints(number: Int): List<Long> = buildList {
    for (f in this@varints) {
        if (f.number != number) continue
        if (f.wire == ProtoWriter.WIRE_VARINT) add(f.varint)
        else if (f.wire == ProtoWriter.WIRE_LENGTH) {
            val packed = f.bytes ?: continue
            var pos = 0
            while (pos < packed.size) {
                var shift = 0
                var value = 0L
                while (true) {
                    val b = packed[pos++].toInt() and 0xFF
                    value = value or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                    if (pos >= packed.size) break
                }
                add(value)
            }
        }
    }
}
