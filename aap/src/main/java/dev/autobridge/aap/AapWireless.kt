package dev.autobridge.aap

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Wireless Android Auto start-up, spoken over a Bluetooth RFCOMM socket before any Android Auto
 * traffic. The car leads:
 *
 * 1. car → WifiStartRequest: the address and TCP port the car listens on.
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
        connect: (Endpoint, WifiInfo) -> AapTransport,
    ): AapTransport {
        var endpoint: Endpoint? = null
        while (true) {
            val message = try {
                read(input)
            } catch (e: EOFException) {
                throw IllegalStateException("car closed Bluetooth before sending its Wi-Fi details")
            }
            when (message.id) {
                WIFI_VERSION_REQUEST -> {
                    log("car asks for the wireless version")
                    write(output, WIFI_VERSION_RESPONSE, ByteArray(0))
                }
                WIFI_START_REQUEST -> {
                    endpoint = parseStart(message.body)
                    log("car listens on ${endpoint.host}:${endpoint.port}")
                    write(output, WIFI_INFO_REQUEST, ByteArray(0))
                }
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
                else -> log("wireless: ignored message ${message.id} (${message.body.size}B)")
            }
        }
    }
}
