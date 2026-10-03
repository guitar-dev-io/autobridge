package dev.autobridge.remotestream

import java.io.EOFException
import java.io.InputStream
import java.security.SecureRandom

/**
 * RFC 6455 framing, by hand.
 *
 * ## Why not a library
 *
 * The receiver needs exactly two things from a WebSocket: binary frames carrying H.264 access
 * units, and text frames carrying JSON control messages. OkHttp would give that, plus an HTTP
 * client the app does not otherwise have — this project talks to the network with
 * `HttpURLConnection` everywhere else, and a transport dependency pulled in for one experimental
 * feature is a dependency the other 99% of the app pays for. The framing itself is small, fully
 * specified and, written this way, testable without a socket: [encode] and [readFrame] are the
 * whole protocol surface and both are covered by unit tests.
 *
 * ## What is implemented
 *
 * Client-to-server frames are always masked, as the RFC requires; server-to-client frames must
 * not be. Continuation frames are reassembled, ping is answered with pong, and close is
 * surfaced. Extensions and subprotocol negotiation are not implemented and are not offered in
 * the handshake, so a compliant server will not use them.
 */
object WebSocketFrame {

    const val OP_CONTINUATION = 0x0
    const val OP_TEXT = 0x1
    const val OP_BINARY = 0x2
    const val OP_CLOSE = 0x8
    const val OP_PING = 0x9
    const val OP_PONG = 0xA

    /**
     * A frame at a size this receiver is willing to buffer.
     *
     * A keyframe at 1080p is comfortably under a megabyte; sixteen is generous for a video frame
     * and still small enough that a hostile or broken host cannot make the app allocate its way
     * out of memory on a single length field.
     */
    const val MAX_PAYLOAD_BYTES = 16 * 1024 * 1024

    private val random = SecureRandom()

    data class Frame(val opcode: Int, val payload: ByteArray, val fin: Boolean) {
        /** Data classes compare arrays by identity; frames are compared by content in tests. */
        override fun equals(other: Any?): Boolean =
            other is Frame && opcode == other.opcode && fin == other.fin &&
                payload.contentEquals(other.payload)

        override fun hashCode(): Int =
            (opcode * 31 + fin.hashCode()) * 31 + payload.contentHashCode()
    }

    /**
     * One masked client frame, ready to write.
     *
     * [mask] is injectable only so a test can assert the exact bytes; production always takes the
     * secure random default, because a predictable mask is the one thing the masking requirement
     * exists to prevent.
     */
    fun encode(opcode: Int, payload: ByteArray, mask: ByteArray = randomMask()): ByteArray {
        require(mask.size == 4) { "mask must be 4 bytes" }
        val header = ArrayList<Byte>(14)
        header.add((0x80 or (opcode and 0x0F)).toByte())

        val length = payload.size
        when {
            length < 126 -> header.add((0x80 or length).toByte())
            length <= 0xFFFF -> {
                header.add((0x80 or 126).toByte())
                header.add((length ushr 8).toByte())
                header.add(length.toByte())
            }
            else -> {
                header.add((0x80 or 127).toByte())
                // 64-bit length: this client never sends more than 2^31, so the top four bytes
                // are zero by construction rather than by truncation.
                repeat(4) { header.add(0) }
                header.add((length ushr 24).toByte())
                header.add((length ushr 16).toByte())
                header.add((length ushr 8).toByte())
                header.add(length.toByte())
            }
        }
        mask.forEach { header.add(it) }

        val out = ByteArray(header.size + length)
        header.forEachIndexed { index, byte -> out[index] = byte }
        for (i in 0 until length) {
            out[header.size + i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        return out
    }

    fun randomMask(): ByteArray = ByteArray(4).also { random.nextBytes(it) }

    /**
     * Reads one frame from [input], blocking until it is complete.
     *
     * @throws EOFException when the stream ends mid-frame, which the caller treats as a
     *   disconnect rather than as corruption.
     * @throws IllegalStateException on a payload larger than [MAX_PAYLOAD_BYTES] or a masked
     *   server frame, both of which mean the peer is not speaking the protocol this expects.
     */
    fun readFrame(input: InputStream): Frame {
        val first = input.readOrThrow()
        val second = input.readOrThrow()
        val fin = (first and 0x80) != 0
        val opcode = first and 0x0F
        val masked = (second and 0x80) != 0
        check(!masked) { "server frames must not be masked" }

        var length = (second and 0x7F).toLong()
        when (length) {
            126L -> {
                length = 0
                repeat(2) { length = (length shl 8) or input.readOrThrow().toLong() }
            }
            127L -> {
                length = 0
                repeat(8) { length = (length shl 8) or input.readOrThrow().toLong() }
            }
        }
        check(length in 0..MAX_PAYLOAD_BYTES.toLong()) { "frame too large: $length" }

        val payload = ByteArray(length.toInt())
        var read = 0
        while (read < payload.size) {
            val count = input.read(payload, read, payload.size - read)
            if (count < 0) throw EOFException("stream ended inside a frame")
            read += count
        }
        return Frame(opcode, payload, fin)
    }

    private fun InputStream.readOrThrow(): Int {
        val value = read()
        if (value < 0) throw EOFException("stream ended")
        return value
    }
}
