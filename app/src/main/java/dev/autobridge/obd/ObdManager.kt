package dev.autobridge.obd

import android.os.Handler
import android.os.Looper
import android.content.Context
import androidx.core.content.ContextCompat
import android.content.Intent
import dev.autobridge.obd.elm.AdapterInfo
import dev.autobridge.obd.log.ObdLog
import dev.autobridge.obd.elm.ElmSession
import dev.autobridge.obd.obd2.Obd2Client
import dev.autobridge.obd.obd2.ObdResult
import dev.autobridge.obd.obd2.PidValue
import dev.autobridge.obd.transport.ObdTransport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Owns the one adapter connection. A single worker thread talks to the adapter, so live polling
 * and one-off requests (read codes, clear codes) never interleave on the wire. Listeners are
 * called on the main thread.
 *
 * Phase 1 keeps the connection in the app process: it lasts as long as the process does. A
 * foreground service to hold it while the app is backgrounded is part of docs/OBD_PLAN.md.
 */
object ObdManager {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    data class Status(
        val state: State = State.DISCONNECTED,
        val adapterName: String? = null,
        val info: AdapterInfo? = null,
        val vin: String? = null,
        val supportedPids: Set<Int> = emptySet(),
        val error: String? = null
    )

    interface Listener {
        fun onStatus(status: Status) {}
        fun onValues(values: Map<Int, PidValue>) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val wanted = ConcurrentHashMap<Any, Set<Int>>()
    private val tasks = LinkedBlockingQueue<(Obd2Client) -> Unit>()

    @Volatile var status = Status()
        private set

    /** Recording of live values; fills only while [recording] is on. */
    val log = ObdLog()
    @Volatile var recording = false
    @Volatile private var transport: ObdTransport? = null
    @Volatile private var stopRequested = false
    private var worker: Thread? = null

    fun addListener(l: Listener) { listeners.addIfAbsent(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    /** Which live PIDs [owner] wants polled; empty set withdraws the request. */
    fun requestPids(owner: Any, pids: Set<Int>) {
        if (pids.isEmpty()) wanted.remove(owner) else wanted[owner] = pids
    }

    @Synchronized
    fun connect(context: Context, newTransport: ObdTransport, adapterName: String) {
        if (worker?.isAlive == true) return
        // The service keeps the process (and so this connection) alive while the app is in the background.
        runCatching { ContextCompat.startForegroundService(context, Intent(context, ObdService::class.java)) }
        stopRequested = false
        tasks.clear()
        transport = newTransport
        publish(Status(State.CONNECTING, adapterName))
        worker = Thread({ run(newTransport, adapterName) }, "obd-worker").also { it.isDaemon = true; it.start() }
    }

    fun disconnect() {
        stopRequested = true
        runCatching { transport?.close() }
    }

    /** Runs [block] on the worker, in order with live polling; [done] gets the result on the main thread. */
    fun <T> submit(block: (Obd2Client) -> T, done: (Result<T>) -> Unit) {
        if (status.state != State.CONNECTED) {
            main.post { done(Result.failure(IllegalStateException("not connected"))) }
            return
        }
        tasks.add { client ->
            val result = runCatching { block(client) }
            main.post { done(result) }
        }
    }

    private fun run(t: ObdTransport, name: String) {
        var error: String? = null
        try {
            val session = ElmSession(t)
            val info = session.connect()
            val client = Obd2Client(session)
            val vin = (client.vin() as? ObdResult.Ok)?.value
            val supported = client.supportedPids()
            publish(Status(State.CONNECTED, name, info, vin, supported))

            var misses = 0
            while (!stopRequested) {
                val ids = wanted.values.flatten().toSet().filter { it in supported }
                val task = tasks.poll(if (ids.isEmpty()) 250 else 0, TimeUnit.MILLISECONDS)
                if (task != null) { task(client); continue }
                if (ids.isEmpty()) continue
                val values = HashMap<Int, PidValue>()
                for (id in ids) {
                    if (stopRequested) break
                    when (val r = client.readPid(id)) {
                        is ObdResult.Ok -> { values[id] = r.value; misses = 0 }
                        is ObdResult.Err -> misses++
                    }
                }
                if (misses >= MAX_MISSES) throw java.io.IOException("The adapter stopped answering.")
                if (recording) log.add(System.currentTimeMillis(), values)
                if (values.isNotEmpty()) main.post { listeners.forEach { it.onValues(values) } }
            }
        } catch (e: Exception) {
            if (!stopRequested) error = e.message ?: e.javaClass.simpleName
        } finally {
            runCatching { t.close() }
            publish(Status(State.DISCONNECTED, name, error = error))
        }
    }

    private fun publish(s: Status) {
        status = s
        main.post { listeners.forEach { it.onStatus(s) } }
    }

    private const val MAX_MISSES = 12
}
