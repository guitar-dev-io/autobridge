package dev.autobridge.aapdirect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.ParcelUuid
import dev.autobridge.aap.AapTransport
import dev.autobridge.aap.AapWireless
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Wireless start-up: a Bluetooth RFCOMM link to the paired car carries the car's Wi-Fi details,
 * then the Android Auto session runs over TCP on the car's Wi-Fi.
 *
 * Cars differ in who opens the Bluetooth link, so both happen at once: this phone listens on the
 * Android Auto wireless service, and also calls each paired device that advertises it. Whichever
 * link the car uses first wins; [onLink] gets the RFCOMM socket and the TCP transport.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is requested on the phone screen before this starts.
class WirelessLink(
    private val context: Context,
    private val onLink: (rfcomm: BluetoothSocket, transport: AapTransport) -> Unit,
) {
    private val uuid: UUID = UUID.fromString(AapWireless.SERVICE_UUID)
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var running = false
    @Volatile private var linked = false
    private val threads = ArrayList<Thread>()

    fun start(): Boolean {
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            DirectLog.add("wireless: Bluetooth is off")
            return false
        }
        running = true
        linked = false
        server = runCatching { bt.listenUsingRfcommWithServiceRecord("Android Auto", uuid) }
            .onFailure { DirectLog.add("wireless: could not listen on Bluetooth: ${it.message}") }
            .getOrNull()
        server?.let { s ->
            DirectLog.add("wireless: listening for the car on Bluetooth")
            spawn("aa-direct-bt-listen") {
                while (running && !linked) {
                    val socket = runCatching { s.accept() }.getOrNull() ?: break
                    DirectLog.add("wireless: car called in: ${name(socket.remoteDevice)}")
                    handle(socket)
                }
            }
        }
        val cars = bt.bondedDevices.orEmpty().filter { device ->
            device.uuids.orEmpty().any { it == ParcelUuid(uuid) }
        }
        if (cars.isEmpty()) DirectLog.add("wireless: no paired device advertises Android Auto wireless yet; waiting for the car to call")
        cars.forEach { device ->
            spawn("aa-direct-bt-call") {
                var attempt = 0
                while (running && !linked && attempt < 20) {
                    attempt++
                    val socket = runCatching {
                        device.createRfcommSocketToServiceRecord(uuid).also { it.connect() }
                    }.getOrNull()
                    if (socket != null) {
                        DirectLog.add("wireless: reached ${name(device)}")
                        handle(socket)
                        return@spawn
                    }
                    Thread.sleep(3000)
                }
            }
        }
        return server != null || cars.isNotEmpty()
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        synchronized(threads) { threads.forEach { it.interrupt() }; threads.clear() }
    }

    private fun handle(socket: BluetoothSocket) {
        synchronized(this) {
            if (linked) { runCatching { socket.close() }; return }
            linked = true
        }
        try {
            val transport = AapWireless.startUp(
                socket.inputStream, socket.outputStream, { DirectLog.add("wireless: $it") },
                serial = android.os.Build.MODEL.orEmpty().ifBlank { "Android" },
                deviceId = DirectStore.deviceId(context),
            ) { endpoint, info ->
                WifiJoin.open(context, endpoint, info)
            }
            runCatching { server?.close() }
            onLink(socket, transport)
        } catch (e: Exception) {
            DirectLog.add("wireless: start-up failed: ${e.message}")
            runCatching { socket.close() }
            linked = false
        }
    }

    private fun spawn(name: String, body: () -> Unit) {
        val t = Thread({ runCatching(body) }, name)
        synchronized(threads) { threads += t }
        t.start()
    }

    private fun name(device: BluetoothDevice): String = runCatching { device.name }.getOrNull() ?: device.address
}

/**
 * Joins the car's Wi-Fi and opens TCP to it. Tries the current network first (the phone may
 * already be on the car's Wi-Fi). Otherwise asks Android for that exact network with a
 * WifiNetworkSpecifier: the first time, Android shows a "connect to device" prompt; after that it
 * reconnects without one. The requested network is held for as long as the transport is open.
 */
object WifiJoin {
    fun open(context: Context, endpoint: AapWireless.Endpoint, info: AapWireless.WifiInfo): AapTransport {
        val address = InetSocketAddress(endpoint.host, endpoint.port)
        runCatching {
            val direct = Socket()
            direct.connect(address, 2500)
            DirectLog.add("wireless: reached the car on the current network")
            return TcpTransport(direct, null, context)
        }
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val spec = WifiNetworkSpecifier.Builder().setSsid(info.ssid).apply {
            runCatching { if (info.bssid.isNotBlank()) setBssid(MacAddress.fromString(info.bssid)) }
            if (!info.open) setWpa2Passphrase(info.key)
        }.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val ready = CountDownLatch(1)
        var network: Network? = null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { network = n; ready.countDown() }
            override fun onUnavailable() { ready.countDown() }
        }
        DirectLog.add("wireless: joining \"${info.ssid}\" (Android may ask once)")
        cm.requestNetwork(request, callback, 60_000)
        if (!ready.await(65, TimeUnit.SECONDS) || network == null) {
            runCatching { cm.unregisterNetworkCallback(callback) }
            throw IllegalStateException("could not join the car's Wi-Fi \"${info.ssid}\"")
        }
        val socket = network!!.socketFactory.createSocket()
        try {
            socket.connect(address, 8000)
        } catch (e: Exception) {
            runCatching { socket.close() }
            runCatching { cm.unregisterNetworkCallback(callback) }
            throw IllegalStateException("joined \"${info.ssid}\" but the car did not answer on ${endpoint.host}:${endpoint.port}")
        }
        DirectLog.add("wireless: connected to the car over Wi-Fi")
        return TcpTransport(socket, callback, context)
    }
}

class TcpTransport(
    private val socket: Socket,
    private val hold: ConnectivityManager.NetworkCallback?,
    private val context: Context,
) : AapTransport {
    init {
        socket.tcpNoDelay = true
    }

    override val input: InputStream = socket.getInputStream().buffered(32 * 1024)
    override val output: OutputStream = socket.getOutputStream()
    override val description: String = "Wi-Fi ${socket.inetAddress?.hostAddress}:${socket.port}"

    override fun close() {
        runCatching { socket.close() }
        hold?.let { cb ->
            runCatching { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
        }
    }
}
