package dev.autobridge.obd

import android.content.Context
import android.os.SystemClock
import dev.autobridge.logging.StructuredLog
import dev.autobridge.obd.profile.VehicleProfile
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
    const val AUTO_GIVE_UP_MS = 10 * 60_000L
    /** [ObdState.message] when auto-connect stopped trying; screens show their own wording. */
    const val GAVE_UP = "gave_up"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(ObdState())
    private val mutableTelemetry = MutableStateFlow(Telemetry())
    private var job: Job? = null
    private var appContext: Context? = null
    @Volatile private var activePoller: ObdPoller? = null

    val state: StateFlow<ObdState> = mutableState.asStateFlow()
    val telemetry: StateFlow<Telemetry> = mutableTelemetry.asStateFlow()

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Starts the connection when the person has not switched auto-connect off and has chosen an
     * adapter and allowed Bluetooth. Used when the car connects and when a gauges screen opens.
     * Gives up after [AUTO_GIVE_UP_MS] without a live link (the car is off or out of range); the
     * next car connection or screen opening tries again. Returns true when a link is running.
     */
    fun startIfWanted(context: Context): Boolean {
        if (isRunning) return true
        val app = context.applicationContext
        if (!ObdSettings.autoConnect(app)) return false
        val adapter = ObdSettings.adapter(app) ?: return false
        if (ObdPermissions.missing(app).isNotEmpty()) return false
        ObdSettings.applyProfile(app)
        start(app, adapter, ObdSettings.profile(app), giveUpAfterMs = AUTO_GIVE_UP_MS)
        return true
    }

    /** [giveUpAfterMs]: stop retrying once this long has passed without a live link; null keeps trying. */
    @Synchronized
    fun start(
        context: Context,
        adapter: ObdSettings.Adapter,
        profile: VehicleProfile,
        giveUpAfterMs: Long? = null,
    ) {
        if (isRunning) return
        val app = context.applicationContext
        appContext = app
        ObdService.start(app)
        mutableTelemetry.value = Telemetry()
        mutableState.value = ObdState(ObdState.Status.CONNECTING, adapterName = ObdAdapterMatcher.display(adapter.name))
        job = scope.launch {
            var pause = 1_000L
            var waitingSince = SystemClock.elapsedRealtime()
            while (true) {
                var wasLive = false
                val reason = attempt(app, adapter, profile) { wasLive = true }
                // The clock for giving up starts when the link was last good, not when it began.
                if (wasLive) waitingSince = SystemClock.elapsedRealtime()
                StructuredLog.w(TAG, "OBD link ended: $reason")
                if (giveUpAfterMs != null && SystemClock.elapsedRealtime() - waitingSince > giveUpAfterMs) {
                    StructuredLog.w(TAG, "OBD auto-connect gave up after ${giveUpAfterMs / 1000}s")
                    ObdService.stop(app)
                    mutableTelemetry.value = Telemetry()
                    mutableState.value = ObdState(message = GAVE_UP)
                    return@launch
                }
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
        profile: VehicleProfile,
        onLive: () -> Unit,
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
                        val poller = ObdPoller(session, profile) { gauge, value ->
                            val now = SystemClock.elapsedRealtime()
                            mutableTelemetry.update { it.with(gauge, value, now).refreshDerived(profile.derived, gauge, now) }
                        }
                        poller.initialize(ObdSettings.lastProtocol(context, adapter.address))
                        poller.protocolDigit?.let { ObdSettings.setLastProtocol(context, adapter.address, it) }
                        onLive()
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
