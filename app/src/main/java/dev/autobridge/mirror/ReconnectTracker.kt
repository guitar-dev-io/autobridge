package dev.autobridge.mirror

/**
 * Tracks Android Auto car-session drops and recoveries so a transient disconnect (the user
 * leaving/re-entering the car app, the host re-creating the surface) resumes the existing
 * [MediaProjection] instead of forcing fresh screen-capture consent.
 *
 * The first surface after a projection is a fresh start even though a projection object already
 * exists. Only a surface arrival after an observed detach counts as a reconnect. This prevents
 * duplicate `onSurfaceAvailable` callbacks from inflating the counter.
 */
object ReconnectTracker {
    enum class Outcome {
        /** No prior attached surface exists for this projection. */
        FRESH_START,

        /** A duplicate callback for the already-attached surface. */
        UNCHANGED,

        /** A previously attached surface was detached while the projection remained alive. */
        RESUMED
    }

    @Volatile
    var reconnectCount: Int = 0
        private set

    private var surfaceAttached = false
    private var surfaceSeen = false

    /** Legacy pure classifier retained for callers/tests that only have projection state. */
    fun classify(projectionAlive: Boolean): Outcome =
        if (projectionAlive) Outcome.RESUMED else Outcome.FRESH_START

    /** Records a surface arrival using the per-projection lifecycle state. */
    @Synchronized
    fun onSurfaceArrived(projectionAlive: Boolean, surfaceChanged: Boolean = true): Outcome {
        if (!surfaceChanged) return Outcome.UNCHANGED
        if (!surfaceAttached) {
            surfaceAttached = true
            val resumed = projectionAlive && surfaceSeen
            surfaceSeen = true
            if (resumed) {
                reconnectCount++
                return Outcome.RESUMED
            }
            return Outcome.FRESH_START
        }
        if (!projectionAlive) {
            return Outcome.FRESH_START
        }
        reconnectCount++
        return Outcome.RESUMED
    }

    @Synchronized
    fun onSurfaceDetached() {
        surfaceAttached = false
    }

    @Synchronized
    fun reset() {
        reconnectCount = 0
        surfaceAttached = false
        surfaceSeen = false
    }
}
