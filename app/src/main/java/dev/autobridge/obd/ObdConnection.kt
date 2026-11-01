package dev.autobridge.obd

import android.content.Context
import android.os.SystemClock
import dev.autobridge.logging.StructuredLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

data class ObdState(
    val status: Status = Status.IDLE,
    val adapterName: String? = null,
    val protocol: String? = null,
    /** Why the last attempt ended, while retrying. */
    val message: String? = null,
) {
    enum class Status { IDLE, CONNECTING, INITIALIZING, LIVE, RETRYING }
}

/**
 * The one connection to the car's adapter. It lives for the process, not for a screen, so the
 * phone screen and the car screen read the same [telemetry]; [start] and [stop] are the only
 * switches. A dropped link is retried with a growing pause until [stop].
 */
object ObdConnection {
    private const val TAG = "ObdConnection"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(ObdState())
    private val mutableTelemetry = MutableStateFlow(Telemetry())
    private var job: Job? = null
    private var appContext: Context? = null
    @Volatile private var activePoller: ObdPoller? = null

    val state: StateFlow<ObdState> = mutableState.asStateFlow()
    val telemetry: StateFlow<Telemetry> = mutableTelemetry.asStateFlow()

    val isRunning: Boolean get() = job?.isActive == true

    @Synchronized
    fun start(context: Context, adapter: ObdSettings.Adapter, transmission: Transmission) {
        if (isRunning) return
        val app = context.applicationContext
        appContext = app
        ObdService.start(app)
        mutableTelemetry.value = Telemetry()
        mutableState.value = ObdState(ObdState.Status.CONNECTING, adapterName = adapter.name)
        job = scope.launch {
            var pause = 1_000L
            while (true) {
                val reason = attempt(app, adapter, transmission)
                StructuredLog.w(TAG, "OBD link ended: $reason")
                mutableState.update {
                    it.copy(status = ObdState.Status.RETRYING, protocol = null, message = reason)
                }
                delay(pause)
                pause = (pause * 2).coerceAtMost(15_000L)
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        appContext?.let { ObdService.stop(it) }
        mutableState.value = ObdState()
        mutableTelemetry.value = Telemetry()
    }

    /** One connection, from opening the link to losing it. Returns why it ended. */
    private suspend fun attempt(
        context: Context,
        adapter: ObdSettings.Adapter,
        transmission: Transmission,
    ): String {
        val transport = BleTransport(context, adapter.address)
        var reason = "Connection ended"
        try {
            coroutineScope {
                val session = ElmSession(transport)
                val worker = launch {
                    try {
                        mutableState.update { it.copy(status = ObdState.Status.CONNECTING, message = null) }
                        transport.connect()
                        mutableState.update { it.copy(status = ObdState.Status.INITIALIZING) }
                        val poller = ObdPoller(session, transmission) { gauge, value ->
                            mutableTelemetry.update { it.with(gauge, value, SystemClock.elapsedRealtime()) }
                        }
                        poller.initialize()
                        activePoller = poller
                        mutableState.update {
                            it.copy(status = ObdState.Status.LIVE, protocol = poller.protocol, message = null)
                        }
                        poller.poll()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ObdTimeoutException) {
                        reason = "The adapter stopped answering"
                    } catch (e: ObdFailure) {
                        reason = e.message ?: reason
                    } catch (e: IOException) {
                        reason = e.message ?: "Bluetooth error"
                    } catch (e: SecurityException) {
                        reason = "Bluetooth permission is missing"
                    } catch (e: Exception) {
                        StructuredLog.w(TAG, "OBD session failed: $e")
                        reason = e.message ?: e.javaClass.simpleName
                    }
                }
                // A dropped link ends the worker; the retry loop in start() then reconnects.
                transport.onClosed = { why ->
                    why?.let { reason = it }
                    worker.cancel()
                }
            }
        } finally {
            activePoller = null
            transport.close()
        }
        return reason
    }

    /** What a trouble-code read found. */
    data class DtcReport(val stored: List<String>, val pending: List<String>)

    /**
     * Asks every control unit for its stored (03) and pending (07) codes. Null when the adapter is
     * not live, so a caller can say "connect first" instead of waiting on nothing.
     */
    suspend fun readDtcs(): DtcReport? {
        val poller = activePoller ?: return null
        return poller.exclusively { session ->
            val can = poller.isCan
            val stored = DtcParser.parse(session.request("03", 4_000), 0x43, can)
            val pending = DtcParser.parse(session.request("07", 4_000), 0x47, can)
            DtcReport(stored, pending)
        }
    }

    /**
     * Clears the stored codes, the freeze frame and the readiness monitors (service 04). True when
     * the car confirmed with 44. The caller is expected to have asked the person first.
     */
    suspend fun clearDtcs(): Boolean? {
        val poller = activePoller ?: return null
        return poller.exclusively { session ->
            session.request("04", 5_000).uppercase().replace(" ", "").contains("44")
        }
    }
}
