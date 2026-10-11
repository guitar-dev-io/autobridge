package dev.autobridge.car

import android.os.SystemClock
import dev.autobridge.logging.StructuredLog

/**
 * What happened to the car connection, in order and with times, so "it keeps dropping" can be
 * answered from a log instead of a memory of the drive.
 *
 * A drop can happen at four layers that recover independently: the Android Auto host connection,
 * this app's car session, the car surface a screen draws on, and the MediaProjection behind
 * mirroring. The vehicle state is recorded alongside them because mirroring is torn down whenever
 * it leaves PARKED, including when a speed reading goes stale and turns UNKNOWN, so a mirror that
 * "drops" may really be a speed signal that went quiet. [ReconnectTracker][dev.autobridge.mirror.ReconnectTracker] only counts surface
 * resumes and is reset with every projection, so it can say neither how long an outage lasted
 * nor which layer it was. Each layer reports here, every event is also one `CONN` line in the
 * structured log (and so in Send log), and [summary] turns the events into the numbers stability
 * work is measured by.
 *
 * Observation only: nothing here retries, reconnects or changes what a gate allows.
 */
object ConnectionTimeline {
    private const val TAG = "CONN"
    private const val CAPACITY = 100
    private const val UNKNOWN = "UNKNOWN"

    enum class Kind {
        HOST_CONNECTED,
        HOST_DISCONNECTED,
        SESSION_STARTED,
        SESSION_ENDED,
        SURFACE_ATTACHED,
        SURFACE_DETACHED,
        SURFACE_RESUMED,
        PROJECTION_STARTED,
        PROJECTION_STOPPED,
        /** The parked status changed; detail is the new state (PARKED, MOVING or UNKNOWN). */
        VEHICLE_STATE
    }

    data class Event(val atMs: Long, val kind: Kind, val detail: String)

    data class Summary(
        /** Host connection lost after having been up. */
        val hostDrops: Int,
        /** Car surface came back to a projection that was still alive (no new consent needed). */
        val surfaceResumes: Int,
        /** Projection ended, by any cause. */
        val projectionStops: Int,
        /** Time from the most recent completed outage (host or surface) to its recovery. */
        val lastRecoveryMs: Long?,
        val longestOutageMs: Long?,
        /** True while the host or the surface is down and has not come back yet. */
        val outageOpen: Boolean,
        /** Times a known speed reading (PARKED or MOVING) turned UNKNOWN. */
        val speedLosses: Int = 0
    )

    private val events = ArrayDeque<Event>()
    private var hostConnected: Boolean? = null

    @Synchronized
    fun record(kind: Kind, detail: String = "", nowMs: Long = SystemClock.elapsedRealtime()) {
        // The host signal repeats its current value on every observer attach; only a change is an event.
        when (kind) {
            Kind.HOST_CONNECTED -> if (hostConnected == true) return else hostConnected = true
            Kind.HOST_DISCONNECTED -> if (hostConnected != true) return else hostConnected = false
            else -> Unit
        }
        if (events.size == CAPACITY) events.removeFirst()
        events.addLast(Event(nowMs, kind, detail))
        StructuredLog.i(TAG, if (detail.isEmpty()) kind.name else "${kind.name} $detail")
    }

    @Synchronized
    fun recent(): List<Event> = events.toList()

    @Synchronized
    fun summary(): Summary {
        var hostDrops = 0
        var surfaceResumes = 0
        var projectionStops = 0
        var speedLosses = 0
        var lastVehicleState: String? = null
        var hostDownAt: Long? = null
        var surfaceDownAt: Long? = null
        var lastRecovery: Long? = null
        var longest: Long? = null

        fun recovered(downAt: Long, upAt: Long) {
            val gap = upAt - downAt
            lastRecovery = gap
            longest = maxOf(longest ?: gap, gap)
        }

        for (event in events) when (event.kind) {
            Kind.HOST_DISCONNECTED -> { hostDrops++; hostDownAt = event.atMs }
            Kind.HOST_CONNECTED -> hostDownAt?.let { recovered(it, event.atMs); hostDownAt = null }
            Kind.SURFACE_DETACHED -> surfaceDownAt = event.atMs
            Kind.SURFACE_RESUMED -> {
                surfaceResumes++
                surfaceDownAt?.let { recovered(it, event.atMs); surfaceDownAt = null }
            }
            // A fresh attach is a new start, not a recovery, but it does close an open detach.
            Kind.SURFACE_ATTACHED -> surfaceDownAt = null
            Kind.PROJECTION_STOPPED -> { projectionStops++; surfaceDownAt = null }
            Kind.VEHICLE_STATE -> {
                if (event.detail == UNKNOWN && lastVehicleState != null && lastVehicleState != UNKNOWN) speedLosses++
                lastVehicleState = event.detail
            }
            else -> Unit
        }
        return Summary(
            hostDrops = hostDrops,
            surfaceResumes = surfaceResumes,
            projectionStops = projectionStops,
            lastRecoveryMs = lastRecovery,
            longestOutageMs = longest,
            outageOpen = hostDownAt != null || surfaceDownAt != null,
            speedLosses = speedLosses
        )
    }

    @Synchronized
    fun clear() {
        events.clear()
        hostConnected = null
    }
}
