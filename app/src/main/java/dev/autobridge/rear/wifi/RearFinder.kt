package dev.autobridge.rear.wifi

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import dev.autobridge.logging.StructuredLog
import java.util.concurrent.Executors

/**
 * Looks for rear-screen tablets on the Wi-Fi (the `_abrear._tcp` services that
 * [RearReceiverActivity] announces), and asks each one who it is.
 *
 * [onFound] runs on the main thread once per tablet, with its address and its `/info`. Android
 * resolves one service at a time, so they are resolved in turn. Some hotspots drop mDNS; the
 * Rear screen page offers adding a tablet by address for those.
 */
class RearFinder(private val context: Context, private val onFound: (Found) -> Unit) {

    data class Found(val host: String, val port: Int, val info: RearProtocol.Info)

    private val manager = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var running = false

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            StructuredLog.w(TAG, "discovery failed $errorCode")
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onServiceFound(service: NsdServiceInfo) = main.post { enqueue(service) }.let { }
        override fun onServiceLost(service: NsdServiceInfo) = Unit
    }

    fun start() {
        if (running || manager == null) return
        running = true
        runCatching { manager.discoverServices(RearProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }
            .onFailure { running = false; StructuredLog.w(TAG, "discovery refused: $it") }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { manager?.stopServiceDiscovery(discovery) }
        pending.clear()
    }

    private fun enqueue(service: NsdServiceInfo) {
        if (!running) return
        pending.addLast(service)
        resolveNext()
    }

    // resolveService is deprecated from API 34 in favour of a callback registration that 29 lacks;
    // one resolve at a time works on every version the app supports.
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving || !running) return
        val next = pending.removeFirstOrNull() ?: return
        resolving = true
        manager?.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) = main.post {
                resolving = false
                resolveNext()
            }.let { }

            override fun onServiceResolved(service: NsdServiceInfo) = main.post {
                resolving = false
                val host = service.host?.hostAddress
                if (host != null) ask(host, service.port)
                resolveNext()
            }.let { }
        })
    }

    /** Asks the tablet who it is, so the list shows its name and the phone can match it later. */
    private fun ask(host: String, port: Int) {
        io.execute {
            val info = RearClient.info(host, port) ?: return@execute
            main.post { if (running) onFound(Found(host, port, info)) }
        }
    }

    private companion object {
        const val TAG = "REAR_WIFI_FIND"
    }
}
