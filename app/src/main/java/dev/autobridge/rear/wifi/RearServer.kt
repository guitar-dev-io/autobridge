package dev.autobridge.rear.wifi

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * The tablet's end: a very small HTTP server that takes commands from a paired phone.
 *
 * Small on purpose. A request is a handful of bytes of JSON and the answer is the same, one at a
 * time, so a single accept thread that handles each connection in turn is enough, and needs no
 * library. [handle] and [readRequest] are kept apart from the socket so the routing and the
 * parsing can be tested on the JVM.
 *
 * [target] is called on the server thread; it hands the work to the main thread itself.
 */
class RearServer(
    private val gate: PairingGate,
    private val info: RearProtocol.Info,
    private val target: Target,
) {
    interface Target {
        fun play(command: RearProtocol.Play)
        fun toggle()
        fun mute()
        fun stop()
        /** Read from the server thread, so it must be safe to call off the main thread. */
        fun status(): RearProtocol.Status
    }

    data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)
    data class Response(val code: Int, val body: String)

    @Volatile private var socket: ServerSocket? = null
    private var thread: Thread? = null

    /** Routes one request. Everything but `/info` and `/pair` needs a paired phone's token. */
    fun handle(request: Request): Response {
        val route = request.method.uppercase() to request.path.substringBefore('?')
        if (route == ("GET" to "/info")) return Response(200, info.toJson())
        if (route == ("POST" to "/pair")) {
            val code = RearProtocol.pairCode(request.body) ?: return Response(400, errorBody("code missing"))
            return when (val result = gate.pair(code)) {
                is PairingGate.Result.Paired -> Response(200, RearProtocol.tokenBody(result.token))
                PairingGate.Result.WrongCode -> Response(403, errorBody("wrong code"))
                PairingGate.Result.Locked -> Response(429, errorBody("too many tries, wait a minute"))
            }
        }
        if (!gate.isAuthorized(request.headers[RearProtocol.TOKEN_HEADER.lowercase()])) {
            return Response(401, errorBody("not paired"))
        }
        return when (route) {
            "POST" to "/play" -> {
                val command = RearProtocol.Play.parse(request.body) ?: return Response(400, errorBody("url missing"))
                target.play(command)
                Response(200, target.status().toJson())
            }
            "POST" to "/toggle" -> { target.toggle(); Response(200, "{}") }
            "POST" to "/mute" -> { target.mute(); Response(200, "{}") }
            "POST" to "/stop" -> { target.stop(); Response(200, "{}") }
            "GET" to "/status" -> Response(200, target.status().toJson())
            else -> Response(404, errorBody("unknown"))
        }
    }

    /**
     * Starts listening on [RearProtocol.PORT], or the next free port of the ten after it, or any
     * free port. Returns the port, for the tablet to show and to announce.
     */
    fun start(): Int {
        // A fresh socket per try: one whose bind failed may not be reusable.
        val candidates: List<Int> = (RearProtocol.PORT until RearProtocol.PORT + 10).toList() + 0
        var server: ServerSocket? = null
        for (port in candidates) {
            val attempt = ServerSocket().apply { reuseAddress = true }
            try {
                attempt.bind(InetSocketAddress(port))
                server = attempt
                break
            } catch (_: java.io.IOException) {
                attempt.close()
            }
        }
        val bound = server ?: error("no port to listen on")
        socket = bound
        thread = Thread({ serve(bound) }, "AutoBridgeRearServer").apply { isDaemon = true; start() }
        return bound.localPort
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        thread = null
    }

    private fun serve(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: SocketException) {
                return // closed by stop()
            }
            runCatching { answer(client) }
            runCatching { client.close() }
        }
    }

    private fun answer(client: Socket) {
        client.soTimeout = 5_000
        val request = readRequest(client.getInputStream())
        val response = request?.let(::handle) ?: Response(400, errorBody("bad request"))
        val body = response.body.toByteArray()
        val head = "HTTP/1.1 ${response.code} ${reason(response.code)}\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray())
            write(body)
            flush()
        }
    }

    companion object {
        private const val MAX_BODY = 16 * 1024

        /** Reads one HTTP/1.1 request; null for anything malformed or too large. */
        fun readRequest(input: InputStream): Request? {
            val stream = BufferedInputStream(input)
            val requestLine = readLine(stream) ?: return null
            val parts = requestLine.split(' ')
            if (parts.size < 2) return null
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(stream) ?: return null
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length < 0 || length > MAX_BODY) return null
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = stream.read(body, read, length - read)
                if (n < 0) return null
                read += n
            }
            return Request(parts[0], parts[1], headers, String(body))
        }

        private fun readLine(stream: InputStream): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val b = stream.read()
                if (b < 0) return if (bytes.size() == 0) null else bytes.toString()
                if (b == '\n'.code) return bytes.toString().trimEnd('\r')
                if (bytes.size() > 8 * 1024) return null
                bytes.write(b)
            }
        }

        private fun errorBody(message: String) = org.json.JSONObject().put("error", message).toString()

        private fun reason(code: Int) = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
            404 -> "Not Found"; 429 -> "Too Many Requests"; else -> "Error"
        }
    }
}
