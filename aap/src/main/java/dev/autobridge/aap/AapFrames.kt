package dev.autobridge.aap

import java.io.EOFException
import java.io.InputStream

/**
 * Android Auto link framing.
 *
 * Every frame is `channel(1) flags(1) size(2, big-endian) [total(4, big-endian)] payload`. The
 * four-byte total is present only on the FIRST frame of a message split over several frames and
 * gives the size of the whole (decrypted) message. A message's payload starts with its two-byte
 * big-endian message id.
 *
 * Flags: bits 0-1 the frame's place in its message (FIRST, LAST, both = a whole message in one
 * frame), bit 2 CONTROL (a channel-control message such as a channel-open on a service channel),
 * bit 3 ENCRYPTED (the payload is TLS records; each frame's records decrypt on their own).
 */
object AapFrame {
    const val FLAG_MIDDLE = 0
    const val FLAG_FIRST = 1
    const val FLAG_LAST = 2
    const val FLAG_BULK = FLAG_FIRST or FLAG_LAST
    const val FLAG_CONTROL = 4
    const val FLAG_ENCRYPTED = 8

    /** Largest plaintext a single frame carries; bigger messages are split. */
    const val MAX_FRAME_PAYLOAD = 0x4000

    const val HEADER_SIZE = 2
}

/** One frame as it came off the wire, payload still encrypted when [encrypted]. */
class RawFrame(val channel: Int, val flags: Int, val totalSize: Int, val payload: ByteArray) {
    val position: Int get() = flags and AapFrame.FLAG_BULK
    val encrypted: Boolean get() = flags and AapFrame.FLAG_ENCRYPTED != 0
    val control: Boolean get() = flags and AapFrame.FLAG_CONTROL != 0
}

object FrameIo {
    fun read(input: InputStream): RawFrame {
        val header = readFully(input, AapFrame.HEADER_SIZE)
        val channel = header[0].toInt() and 0xFF
        val flags = header[1].toInt() and 0xFF
        val first = flags and AapFrame.FLAG_BULK == AapFrame.FLAG_FIRST
        val sizeBytes = readFully(input, if (first) 6 else 2)
        val size = ((sizeBytes[0].toInt() and 0xFF) shl 8) or (sizeBytes[1].toInt() and 0xFF)
        val total = if (first) {
            ((sizeBytes[2].toInt() and 0xFF) shl 24) or ((sizeBytes[3].toInt() and 0xFF) shl 16) or
                ((sizeBytes[4].toInt() and 0xFF) shl 8) or (sizeBytes[5].toInt() and 0xFF)
        } else size
        return RawFrame(channel, flags, total, readFully(input, size))
    }

    /** Header + size field(s) + payload for one frame. [totalSize] is used on FIRST frames only. */
    fun encode(channel: Int, flags: Int, payload: ByteArray, totalSize: Int): ByteArray {
        require(payload.size <= 0xFFFF) { "frame payload ${payload.size} does not fit the 16-bit size" }
        val first = flags and AapFrame.FLAG_BULK == AapFrame.FLAG_FIRST
        val headerSize = AapFrame.HEADER_SIZE + if (first) 6 else 2
        val out = ByteArray(headerSize + payload.size)
        out[0] = channel.toByte()
        out[1] = flags.toByte()
        out[2] = (payload.size ushr 8).toByte()
        out[3] = payload.size.toByte()
        if (first) {
            out[4] = (totalSize ushr 24).toByte()
            out[5] = (totalSize ushr 16).toByte()
            out[6] = (totalSize ushr 8).toByte()
            out[7] = totalSize.toByte()
        }
        System.arraycopy(payload, 0, out, headerSize, payload.size)
        return out
    }

    fun readFully(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(buffer, read, size - read)
            if (n < 0) throw EOFException("link closed after $read of $size bytes")
            read += n
        }
        return buffer
    }
}

/** A whole, decrypted message: its channel, whether it was a channel-control message, id and body. */
class AapMessage(val channel: Int, val control: Boolean, val encrypted: Boolean, val id: Int, val body: ByteArray) {
    override fun toString(): String =
        "ch=$channel id=0x${id.toString(16)}${if (control) " ctl" else ""}${if (encrypted) " enc" else ""} ${body.size}B"

    companion object {
        fun parse(channel: Int, control: Boolean, encrypted: Boolean, payload: ByteArray): AapMessage {
            if (payload.size < 2) throw ProtoException("message on channel $channel shorter than its id")
            val id = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
            return AapMessage(channel, control, encrypted, id, payload.copyOfRange(2, payload.size))
        }
    }
}
