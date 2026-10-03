package dev.autobridge.remotestream

import android.util.Base64
import dev.autobridge.bridge.BridgeLog
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.SSLSocketFactory

/**
 * The link to a stream host: video in, control messages out.
 *
 * ## Why this is an interface
 *
 * The spec's preferred transport is WebRTC, with RTSP as the alternative, and the right answer
 * is not knowable before a host exists to try them against. What *is* knowable now is the shape
 * of the thing the receiver needs: connect, hand me access units, take my control messages, tell
 * me when you drop. Everything above this — the decoder, the status machine, the reconnect
 * policy, the car UI — is written against that shape, so swapping the transport is a new class
 * and a line in [RemoteStreamConfig], not a rewrite.
 *
 * [WebSocketTransport] is the first implementation because it is the one that can be stood up
 * against an off-the-shelf host today: any `ws` library plus an encoder is a working peer, and
 * the same socket carries the control channel, so command latency is the video's latency and no
 * second connection has to be kept alive. WebRTC's advantages over it are real but all concern
 * the parts a PoC does not have yet — NAT traversal, congestion control, jitter buffering — and
 * it brings a ~25 MB native dependency and a signalling server with it. See
 * `docs/REMOTE_STREAM.md`.
 */
interface RemoteStreamTransport {

    interface Listener {
        /** One H.264 access unit in Annex-B form, on the transport's reader thread. */
        fun onVideoFrame(data: ByteArray)

        /** A text message from the host, on the transport's reader thread. */
        fun onHostMessage(text: String)

        fun onConnected()

        /** [cause] is null for a clean close by either side. */
        fun onDisconnected(cause: Throwable?)
    }

    fun connect(listener: Listener)

    /** Best effort; a send on a dropped link is dropped rather than throwing at the caller. */
    fun send(message: ControlMessage)

    fun close()
}

/**
 * [RemoteStreamTransport] over a WebSocket, framed by [WebSocketFrame].
 *
 * Binary frames are video, text frames are the host's status. Control messages go the other way
 * as text. The reader runs on its own thread and reports through the listener; nothing here
 * touches a UI or a decoder directly.
 */
class WebSocketTransport(private val endpoint: String) : RemoteStreamTransport {

    private companion object {
        /** The fixed value RFC 6455 appends before hashing the client key. */
        const val GUID = "258EAFA5-E914-47DA-95CA-5AB0DC85B11F"
        const val CONNECT_TIMEOUT_MS = 8_000
        /**
         * A host that has gone quiet without closing the TCP connection is indistinguishable from
         * one that is simply between frames, until a read times out. Long enough not to trip on a
         * stall, short enough that a dead link is noticed within a few seconds of a buffering
         * spinner appearing.
         */
        const val READ_TIMEOUT_MS = 15_000
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var closed = false
    private var reader: Thread? = null
    private val writeLock = Any()

    override fun connect(listener: RemoteStreamTransport.Listener) {
        closed = false
        reader = Thread({ run(listener) }, "AutoBridge-RemoteStream").apply {
            isDaemon = true
            start()
        }
    }

    private fun run(listener: RemoteStreamTransport.Listener) {
        var failure: Throwable? = null
        try {
            val uri = URI(endpoint)
            val secure = uri.scheme.equals("wss", ignoreCase = true)
            val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
            val host = requireNotNull(uri.host) { "no host in $endpoint" }

            val plain = Socket()
            plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            plain.soTimeout = READ_TIMEOUT_MS
            plain.tcpNoDelay = true
            val connection = if (secure) {
                (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(plain, host, port, true)
            } else {
                plain
            }
            socket = connection

            val stream = BufferedInputStream(connection.getInputStream())
            val out = connection.getOutputStream()
            output = out

            val key = handshakeKey()
            out.write(handshakeRequest(uri, host, port, key).toByteArray(Charsets.ISO_8859_1))
            out.flush()
            verifyHandshake(readHandshakeResponse(stream), key)

            BridgeLog.i("remote.transport_connected", "endpoint" to endpoint)
            listener.onConnected()
            readLoop(stream, listener)
        } catch (error: Throwable) {
            if (!closed) failure = error
        } finally {
            closeQuietly()
            listener.onDisconnected(failure)
        }
    }

    private fun readLoop(stream: BufferedInputStream, listener: RemoteStreamTransport.Listener) {
        // Continuation frames are rare from an encoder but legal, so a partial message is
        // accumulated rather than delivered in pieces the decoder could not use.
        var pendingOpcode = -1
        val pending = java.io.ByteArrayOutputStream()

        while (!closed) {
            val frame = try {
                WebSocketFrame.readFrame(stream)
            } catch (end: EOFException) {
                return
            }
            when (frame.opcode) {
                WebSocketFrame.OP_PING -> writeFrame(WebSocketFrame.OP_PONG, frame.payload)
                WebSocketFrame.OP_PONG -> Unit
                WebSocketFrame.OP_CLOSE -> {
                    writeFrame(WebSocketFrame.OP_CLOSE, ByteArray(0))
                    return
                }
                WebSocketFrame.OP_CONTINUATION -> {
                    pending.write(frame.payload)
                    if (frame.fin) {
                        deliver(pendingOpcode, pending.toByteArray(), listener)
                        pending.reset()
                        pendingOpcode = -1
                    }
                }
                else -> {
                    if (frame.fin) {
                        deliver(frame.opcode, frame.payload, listener)
                    } else {
                        pendingOpcode = frame.opcode
                        pending.reset()
                        pending.write(frame.payload)
                    }
                }
            }
        }
    }

    private fun deliver(opcode: Int, payload: ByteArray, listener: RemoteStreamTransport.Listener) {
        when (opcode) {
            WebSocketFrame.OP_BINARY -> listener.onVideoFrame(payload)
            WebSocketFrame.OP_TEXT -> listener.onHostMessage(String(payload, Charsets.UTF_8))
        }
    }

    override fun send(message: ControlMessage) {
        writeFrame(WebSocketFrame.OP_TEXT, message.encode().toByteArray(Charsets.UTF_8))
    }

    private fun writeFrame(opcode: Int, payload: ByteArray) {
        val out = output ?: return
        // One writer at a time: control messages come from the main thread and pongs from the
        // reader, and two interleaved frames on one socket is a protocol error, not a race to
        // debug later.
        synchronized(writeLock) {
            runCatching {
                out.write(WebSocketFrame.encode(opcode, payload))
                out.flush()
            }.onFailure {
                BridgeLog.w("remote.send_failed", "reason" to it.message)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { writeFrame(WebSocketFrame.OP_CLOSE, ByteArray(0)) }
        closeQuietly()
    }

    private fun closeQuietly() {
        runCatching { socket?.close() }
        socket = null
        output = null
    }

    // ---------------------------------------------------------------------------- handshake

    private fun handshakeKey(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun handshakeRequest(uri: URI, host: String, port: Int, key: String): String {
        val path = buildString {
            append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }
        val hostHeader = if (port == 80 || port == 443) host else "$host:$port"
        return buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(hostHeader).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("\r\n")
        }
    }

    private fun readHandshakeResponse(stream: BufferedInputStream): String {
        val builder = StringBuilder()
        // Read a byte at a time to the blank line: the body that follows is the first video frame
        // and must not be swallowed into a buffer this function discards.
        while (!builder.endsWith("\r\n\r\n")) {
            val value = stream.read()
            if (value < 0) throw EOFException("handshake truncated")
            builder.append(value.toChar())
            if (builder.length > 8192) error("handshake response too long")
        }
        return builder.toString()
    }

    private fun verifyHandshake(response: String, key: String) {
        val statusLine = response.lineSequence().firstOrNull().orEmpty()
        check(statusLine.contains(" 101")) { "host refused the upgrade: ${statusLine.trim()}" }
        val accept = response.lineSequence()
            .firstOrNull { it.startsWith("Sec-WebSocket-Accept:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        check(accept == expectedAccept(key)) { "handshake accept mismatch" }
    }

    /** The `Sec-WebSocket-Accept` value a compliant server must return for [key]. */
    fun expectedAccept(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }
}
