package dev.autobridge.remotestream

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import dev.autobridge.bridge.BridgeLog

/**
 * Keeps a stream host connected, decoded and on screen.
 *
 * It owns the three moving parts and nothing else: a [RemoteStreamTransport] for the link, an
 * [H264Decoder] for the pixels, and a status machine with a reconnect timer in front of both.
 * Everything specific to a car, a screen or a playback engine is outside it, which is what makes
 * the whole remote-stream feature removable without touching browser or player code.
 *
 * ## Status
 *
 * The five states from the spec are reported through [onStatus]. The distinction that costs real
 * work is [RemoteStreamStatus.BUFFERING]: a link that is up but has delivered no frame for
 * [STALL_TIMEOUT_MS] is stalled, not connected, and the user should see that rather than a frozen
 * last frame with no explanation. A watchdog on the main thread checks for it, because no
 * callback fires when nothing arrives.
 *
 * ## Reconnect
 *
 * Any drop that was not a [stop] schedules another attempt on [ReconnectPolicy]'s schedule. The
 * surface survives reconnects — it belongs to the car screen, not to the stream — so a host that
 * comes back resumes into the same output with no interaction.
 */
class RemoteStreamReceiver(
    private val endpoint: String,
    private val transportFactory: (String) -> RemoteStreamTransport = { WebSocketTransport(it) }
) {

    private companion object {
        /** No frame for this long on a live link means stalled, not idle. */
        const val STALL_TIMEOUT_MS = 3_000L
        const val WATCHDOG_INTERVAL_MS = 1_000L
    }

    private val main = Handler(Looper.getMainLooper())

    var onStatus: ((RemoteStreamStatus, String?) -> Unit)? = null
    var onHostStatus: ((HostStatusMessage) -> Unit)? = null

    private var transport: RemoteStreamTransport? = null
    private val decoder = H264Decoder { reason -> fail(reason) }

    @Volatile private var status = RemoteStreamStatus.DISCONNECTED
    @Volatile private var userStopped = false
    @Volatile private var attempt = 0
    @Volatile private var lastFrameAtMs = 0L

    val currentStatus: RemoteStreamStatus get() = status
    val framesDecoded: Long get() = decoder.framesDecoded
    val framesDropped: Long get() = decoder.framesDropped

    fun setSurface(surface: Surface?) {
        decoder.setSurface(surface)
        BridgeLog.i("remote.surface", "attached" to (surface != null))
    }

    fun start() {
        userStopped = false
        attempt = 0
        connect()
    }

    private fun connect() {
        if (userStopped) return
        transport?.close()
        setStatus(RemoteStreamStatus.CONNECTING, null)
        BridgeLog.i("remote.connecting", "endpoint" to endpoint, "attempt" to attempt)

        val next = transportFactory(endpoint)
        transport = next
        next.connect(object : RemoteStreamTransport.Listener {
            override fun onConnected() {
                main.post {
                    attempt = 0
                    lastFrameAtMs = SystemClock.elapsedRealtime()
                    setStatus(RemoteStreamStatus.CONNECTED, null)
                    startWatchdog()
                }
            }

            override fun onVideoFrame(data: ByteArray) {
                lastFrameAtMs = SystemClock.elapsedRealtime()
                decoder.submit(data)
                if (status == RemoteStreamStatus.BUFFERING) {
                    main.post { setStatus(RemoteStreamStatus.CONNECTED, null) }
                }
            }

            override fun onHostMessage(text: String) {
                val message = HostStatusMessage.decode(text) ?: return
                main.post {
                    if (message.error != null) {
                        BridgeLog.w("remote.host_error", "reason" to message.error)
                    }
                    onHostStatus?.invoke(message)
                }
            }

            override fun onDisconnected(cause: Throwable?) {
                main.post {
                    decoder.stop()
                    if (userStopped) {
                        setStatus(RemoteStreamStatus.DISCONNECTED, null)
                        return@post
                    }
                    BridgeLog.w("remote.disconnected", "reason" to (cause?.message ?: "clean close"))
                    setStatus(
                        if (cause == null) RemoteStreamStatus.DISCONNECTED else RemoteStreamStatus.ERROR,
                        cause?.message
                    )
                    scheduleReconnect()
                }
            }
        })
    }

    private fun scheduleReconnect() {
        if (!ReconnectPolicy.shouldRetry(status, userStopped)) return
        attempt++
        val delay = ReconnectPolicy.delayMs(attempt)
        BridgeLog.i("remote.reconnect_scheduled", "attempt" to attempt, "delayMs" to delay)
        main.postDelayed(reconnectRunnable, delay)
    }

    private val reconnectRunnable = Runnable { connect() }

    private val watchdog = object : Runnable {
        override fun run() {
            if (userStopped) return
            if (status == RemoteStreamStatus.CONNECTED &&
                SystemClock.elapsedRealtime() - lastFrameAtMs > STALL_TIMEOUT_MS
            ) {
                setStatus(RemoteStreamStatus.BUFFERING, null)
            }
            main.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private fun startWatchdog() {
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    /** Sends a command back to the host. Dropped silently when the link is down. */
    fun send(message: ControlMessage) {
        val link = transport
        if (link == null || status == RemoteStreamStatus.DISCONNECTED) {
            BridgeLog.w("remote.control_dropped", "type" to message.type)
            return
        }
        BridgeLog.i("remote.control", "type" to message.type)
        link.send(message)
    }

    private fun fail(reason: String) {
        main.post { setStatus(RemoteStreamStatus.ERROR, reason) }
    }

    /** User-initiated stop: no reconnect follows. */
    fun stop() {
        userStopped = true
        main.removeCallbacks(reconnectRunnable)
        main.removeCallbacks(watchdog)
        transport?.close()
        transport = null
        decoder.stop()
        setStatus(RemoteStreamStatus.DISCONNECTED, null)
        BridgeLog.i("remote.stopped", "decoded" to framesDecoded, "dropped" to framesDropped)
    }

    fun release() {
        stop()
        decoder.release()
        onStatus = null
        onHostStatus = null
    }

    private fun setStatus(next: RemoteStreamStatus, detail: String?) {
        if (status == next) return
        status = next
        BridgeLog.i("remote.status", "status" to next, "detail" to detail)
        onStatus?.invoke(next, detail)
    }
}
