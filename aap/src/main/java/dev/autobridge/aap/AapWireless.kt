package dev.autobridge.aap

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Wireless Android Auto start-up, spoken over a Bluetooth RFCOMM socket before any Android Auto
 * traffic. The car leads:
 *
 * 0. Many cars first send WifiVersionRequest (their wireless protocol version, often their own
 *    make/model, and on some revisions the address and port as well) and wait for a
 *    WifiVersionResponse with the version and status 0 before going on.
 * 1. car → WifiStartRequest: the address and TCP port the car listens on. A car that already put
 *    them in its WifiVersionRequest may not send this; the phone then goes on by itself.
 * 2. phone → WifiInfoRequest; car → WifiInfoResponse: the car's Wi-Fi SSID, key, BSSID.
 * 3. The phone joins that Wi-Fi and opens TCP to the address; the ordinary Android Auto session
 *    (version, TLS, discovery…) then runs over that socket exactly as over USB.
 * 4. phone → WifiStartResponse and WifiConnectStatus to tell the car it made it.
 *
 * Each message is `length(2) id(2) protobuf`, big-endian, length counting the protobuf only.
 */
object AapWireless {
    /** The RFCOMM service a wireless head unit offers (and a phone listens on). */
    const val SERVICE_UUID = "4de17a00-52cb-11e6-bdf4-0800200c9a66"

    const val WIFI_START_REQUEST = 1
    const val WIFI_INFO_REQUEST = 2
    const val WIFI_INFO_RESPONSE = 3
    const val WIFI_VERSION_REQUEST = 4
    const val WIFI_VERSION_RESPONSE = 5
    const val WIFI_CONNECT_STATUS = 6
    const val WIFI_START_RESPONSE = 7
    const val WIFI_PING_REQUEST = 8
    const val WIFI_PING_RESPONSE = 9
    const val WIFI_SETUP_INFO = 11

    /** SecurityMode values from WifiInfoResponse. */
    const val SECURITY_OPEN = 1
    const val SECURITY_WPA2_PERSONAL = 8
    const val SECURITY_WPA_WPA2_PERSONAL = 12

    data class Endpoint(val host: String, val port: Int)

    data class WifiInfo(val ssid: String, val key: String, val bssid: String, val security: Int) {
        val open: Boolean get() = security == SECURITY_OPEN || key.isEmpty()
        override fun toString(): String = "ssid=\"$ssid\" bssid=$bssid security=$security"
    }

    class Message(val id: Int, val body: ByteArray)

    fun read(input: InputStream): Message {
        val header = FrameIo.readFully(input, 4)
        val length = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
        val id = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        return Message(id, FrameIo.readFully(input, length))
    }

    fun write(output: OutputStream, id: Int, body: ByteArray) {
        val out = ByteArray(4 + body.size)
        out[0] = (body.size ushr 8).toByte()
        out[1] = body.size.toByte()
        out[2] = (id ushr 8).toByte()
        out[3] = id.toByte()
        System.arraycopy(body, 0, out, 4, body.size)
        output.write(out)
        output.flush()
    }

    /** What a car says in WifiVersionRequest. [endpoint] is set by revisions that carry it. */
    data class VersionRequest(
        val major: Int,
        val minor: Int,
        val endpoint: Endpoint?,
        val carInfo: String,
    )

    fun parseVersionRequest(body: ByteArray): VersionRequest {
        val f = runCatching { ProtoReader.fields(body) }.getOrDefault(emptyList())
        var endpoint: Endpoint? = null
        val info = ArrayList<String>()
        for (field in f.filter { it.wire == ProtoWriter.WIRE_LENGTH && (it.number == 4 || it.number == 5) }) {
            val bytes = field.bytes ?: continue
            val inner = runCatching { ProtoReader.fields(bytes) }.getOrNull() ?: continue
            // WifiProjectionProtocolInfo: ip (1, string) + port (2, varint).
            val ip = inner.first(1)?.takeIf { it.wire == ProtoWriter.WIRE_LENGTH }?.string
            val port = inner.first(2)?.takeIf { it.wire == ProtoWriter.WIRE_VARINT }?.int
            if (ip != null && port != null && port in 1..65535 && ip.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) {
                endpoint = Endpoint(ip, port)
            } else {
                // HeadUnitInfo: readable strings such as make, model, year, software version.
                inner.filter { it.wire == ProtoWriter.WIRE_LENGTH }.map { it.string }
                    .filter { it.isNotBlank() && it.all { c -> c.code in 32..126 } }
                    .forEach { info += it }
            }
        }
        return VersionRequest(
            major = f.first(1)?.takeIf { it.wire == ProtoWriter.WIRE_VARINT }?.int ?: 1,
            minor = f.first(2)?.takeIf { it.wire == ProtoWriter.WIRE_VARINT }?.int ?: 0,
            endpoint = endpoint,
            carInfo = info.joinToString(" "),
        )
    }

    /**
     * WifiVersionResponse: the car's own version echoed back, the phone's serial, status 0 (OK)
     * and device info (a stable id for this phone and one for this connection).
     */
    fun versionResponse(major: Int, minor: Int, serial: String, deviceId: String, connectionId: String): ByteArray =
        ProtoWriter()
            .uint(1, major)
            .uint(2, minor)
            .string(3, serial)
            .int(4, 0)
            .message(6, ProtoWriter().string(1, deviceId).string(2, connectionId))
            .toByteArray()

    fun pingResponse(requestBody: ByteArray): ByteArray {
        val ts = runCatching { ProtoReader.fields(requestBody).first(1)?.varint }.getOrNull() ?: 0L
        return ProtoWriter().int(1, ts).toByteArray()
    }

    private fun hex(bytes: ByteArray, max: Int = 48): String =
        bytes.take(max).joinToString("") { "%02x".format(it) } + if (bytes.size > max) "…" else ""

    fun parseStart(body: ByteArray): Endpoint {
        val f = ProtoReader.fields(body)
        return Endpoint(f.first(1)?.string.orEmpty(), f.first(2)?.int ?: 0)
    }

    fun parseInfo(body: ByteArray): WifiInfo {
        val f = ProtoReader.fields(body)
        return WifiInfo(
            ssid = f.first(1)?.string.orEmpty(),
            key = f.first(2)?.string.orEmpty(),
            bssid = f.first(3)?.string.orEmpty(),
            security = f.first(4)?.int ?: 0,
        )
    }

    fun startResponse(endpoint: Endpoint, ok: Boolean): ByteArray =
        ProtoWriter().string(1, endpoint.host).int(2, endpoint.port).int(3, if (ok) 0 else -1).toByteArray()

    fun connectStatus(ok: Boolean): ByteArray = ProtoWriter().int(1, if (ok) 0 else -1).toByteArray()

    /**
     * Runs the RFCOMM exchange. [connect] joins the car's Wi-Fi and opens TCP; it returns the
     * open transport or throws. The RFCOMM socket is left for the caller to close: some cars end
     * the wireless session when it drops, so it is kept open for as long as the session runs.
     */
    fun startUp(
        input: InputStream,
        output: OutputStream,
        log: (String) -> Unit,
        serial: String = "AutoBridge",
        deviceId: String = "autobridge",
        connect: (Endpoint, WifiInfo) -> AapTransport,
    ): AapTransport {
        var endpoint: Endpoint? = null
        var infoAsked = false
        fun askForWifi() {
            if (infoAsked) return
            infoAsked = true
            write(output, WIFI_INFO_REQUEST, ByteArray(0))
        }
        while (true) {
            val message = try {
                read(input)
            } catch (e: EOFException) {
                throw IllegalStateException("car closed Bluetooth before sending its Wi-Fi details")
            }
            when (message.id) {
                WIFI_VERSION_REQUEST -> {
                    val request = parseVersionRequest(message.body)
                    log("car asks for the wireless version: ${request.major}.${request.minor} ${request.carInfo}".trim())
                    write(
                        output, WIFI_VERSION_RESPONSE,
                        versionResponse(request.major, request.minor, serial, deviceId, java.util.UUID.randomUUID().toString()),
                    )
                    request.endpoint?.let {
                        // This revision carries the address already; some of these cars never send
                        // WifiStartRequest, so go on and ask for the Wi-Fi details now.
                        endpoint = it
                        log("car listens on ${it.host}:${it.port} (from its version request)")
                        askForWifi()
                    }
                }
                WIFI_START_REQUEST -> {
                    val start = parseStart(message.body)
                    endpoint = start
                    log("car listens on ${start.host}:${start.port}")
                    askForWifi()
                }
                WIFI_PING_REQUEST -> write(output, WIFI_PING_RESPONSE, pingResponse(message.body))
                WIFI_PING_RESPONSE -> Unit
                WIFI_SETUP_INFO -> log("car sent setup info (${message.body.size}B) ${hex(message.body)}")
                WIFI_INFO_RESPONSE -> {
                    val info = parseInfo(message.body)
                    val target = endpoint ?: throw IllegalStateException("car sent Wi-Fi details before its address")
                    log("car Wi-Fi: $info")
                    val transport = try {
                        connect(target, info)
                    } catch (e: Exception) {
                        runCatching { write(output, WIFI_CONNECT_STATUS, connectStatus(false)) }
                        throw e
                    }
                    runCatching {
                        write(output, WIFI_START_RESPONSE, startResponse(target, true))
                        write(output, WIFI_CONNECT_STATUS, connectStatus(true))
                    }
                    return transport
                }
                else -> log("ignored message ${message.id} (${message.body.size}B) ${hex(message.body)}")
            }
        }
    }
}
