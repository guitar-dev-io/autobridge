package dev.autobridge.rear.wifi

import java.net.HttpURLConnection
import java.net.URL

/**
 * The phone's end of the rear-screen protocol: plain HTTP calls to a tablet. Blocking, so call it
 * off the main thread ([RearSender] does).
 */
object RearClient {
    private const val TIMEOUT_MS = 3_000

    data class Reply(val code: Int, val body: String) {
        val ok: Boolean get() = code in 200..299
    }

    fun info(host: String, port: Int): RearProtocol.Info? =
        call(host, port, "GET", "/info")?.takeIf { it.ok }?.let { RearProtocol.Info.parse(it.body) }

    sealed interface PairResult {
        data class Paired(val token: String) : PairResult
        data object WrongCode : PairResult
        data object Locked : PairResult
        data object Unreachable : PairResult
    }

    fun pair(host: String, port: Int, code: String): PairResult {
        val reply = call(host, port, "POST", "/pair", RearProtocol.pairBody(code)) ?: return PairResult.Unreachable
        return when (reply.code) {
            200 -> RearProtocol.token(reply.body)?.let { PairResult.Paired(it) } ?: PairResult.Unreachable
            429 -> PairResult.Locked
            else -> PairResult.WrongCode
        }
    }

    fun play(tablet: RearTablets.Tablet, command: RearProtocol.Play): Reply? =
        call(tablet.host, tablet.port, "POST", "/play", command.toJson(), tablet.token)

    /** `/toggle`, `/mute` or `/stop`. */
    fun command(tablet: RearTablets.Tablet, path: String): Reply? =
        call(tablet.host, tablet.port, "POST", path, "{}", tablet.token)

    fun status(tablet: RearTablets.Tablet): RearProtocol.Status? =
        call(tablet.host, tablet.port, "GET", "/status", token = tablet.token)
            ?.takeIf { it.ok }?.let { RearProtocol.Status.parse(it.body) }

    /** Null when the tablet cannot be reached at all; otherwise its answer, error codes included. */
    fun call(host: String, port: Int, method: String, path: String, body: String? = null, token: String? = null): Reply? {
        if (host.isBlank()) return null
        val connection = runCatching { URL("http", host, port, path).openConnection() as HttpURLConnection }
            .getOrNull() ?: return null
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = method
            connection.useCaches = false
            token?.let { connection.setRequestProperty(RearProtocol.TOKEN_HEADER, it) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            Reply(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
