package dev.autobridge.mirror

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Surface
import dev.autobridge.core.model.Feature
import dev.autobridge.core.model.RotationMode
import dev.autobridge.core.model.ScaleMode
import dev.autobridge.core.model.Size
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.display.MirrorDiagnostics
import dev.autobridge.display.ScreenOffController
import dev.autobridge.display.ScreenPowerController
import dev.autobridge.display.StructuredLog
import dev.autobridge.display.SurfaceProfile
import dev.autobridge.input.DisplayTransform
import dev.autobridge.safety.ParkingStateStore
import dev.autobridge.safety.SafetyEnforcement

/**
 * Joins the phone-side MediaProjection session to the Surface supplied by Android Auto.
 *
 * AUTO_MIRROR remains the default zero-copy path. SELF_DRAWN uses a separate capture VirtualDisplay
 * feeding [SelfDrawnMirrorEngine], so the two producers never write the car Surface concurrently.
 */
object MirrorCoordinator {
    private const val TAG = "AutoBridgeMirror"
    private val lock = Any()

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var selfDrawnEngine: SelfDrawnMirrorEngine? = null
    private var carSurface: Surface? = null
    private var carWidth: Int = 0
    private var carHeight: Int = 0
    private var carDpi: Int = 0
    private var requestedScaleMode: ScaleMode = ScaleMode.FIT
    private var rotationMode: RotationMode = RotationMode.AUTO

    private val parkingListener: (ParkingStateStore.State) -> Unit = { state ->
        if (state != ParkingStateStore.State.PARKED) {
            // Safety teardown is intentionally stronger than merely hiding the output. It also
            // releases the WakeLock and projection so UNKNOWN cannot leave a live capture session.
            ScreenPowerController.stop()
            stopProjection()
        } else {
            runLocked { reconcileLocked() }
        }
    }

    init {
        ParkingStateStore.addListener(parkingListener)
    }

    val isProjectionReady: Boolean
        get() = synchronized(lock) { projection != null }

    val isCarSurfaceReady: Boolean
        get() = synchronized(lock) { carSurface?.isValid == true }

    val isMirroring: Boolean
        get() = synchronized(lock) { isMirroringLocked() }

    val requestedScale: ScaleMode
        get() = synchronized(lock) { requestedScaleMode }

    val activeRotationMode: RotationMode
        get() = synchronized(lock) { rotationMode }

    val activePipelineMode: ScreenOffController.PipelineMode
        get() = synchronized(lock) { ScreenOffController.pipelineMode }

    val activeSourceSize: Size?
        get() = synchronized(lock) {
            if (isSelfDrawnLocked()) selfDrawnEngine?.sourceSize() else null
        }

    private fun canRenderLocked(): Boolean =
        FeaturePolicy.app.isAvailable(Feature.MIRROR) &&
            SafetyEnforcement.gateParked(ParkingStateStore.isParked)

    private fun isSelfDrawnLocked(): Boolean =
        ScreenOffController.pipelineMode == ScreenOffController.PipelineMode.SELF_DRAWN

    private fun isMirroringLocked(): Boolean {
        if (projection == null || carSurface?.isValid != true || !canRenderLocked()) return false
        return when {
            isSelfDrawnLocked() -> selfDrawnEngine?.isRenderingReady() == true
            ScreenOffController.pipelineMode == ScreenOffController.PipelineMode.AUTO_MIRROR ->
                virtualDisplay != null
            else -> false
        }
    }

    /** Runs [block] under [lock] and reports to diagnostics if mirroring changes. */
    private fun runLocked(block: () -> Unit) {
        runLockedResult {
            block()
            Unit
        }
    }

    private fun <T> runLockedResult(block: () -> T): T = synchronized(lock) {
        val before = isMirroringLocked()
        val result = block()
        val after = isMirroringLocked()
        if (before != after) MirrorDiagnostics.onMirroringActiveChanged(after)
        result
    }

    /**
     * Changes the active pipeline only while no projection is running. A live projection must be
     * stopped and explicitly re-consented before changing between AUTO_MIRROR and SELF_DRAWN.
     */
    fun setPipelineMode(mode: ScreenOffController.PipelineMode): Boolean = runLockedResult {
        if (projection != null) {
            StructuredLog.w(TAG, "Pipeline change to $mode requires stopping the active projection")
            return@runLockedResult false
        }
        if (!ScreenOffController.isAvailable(mode)) {
            StructuredLog.w(TAG, "Pipeline $mode is not available without a dedicated own-content display")
            return@runLockedResult false
        }
        ScreenOffController.pipelineMode = mode
        DisplayTransform.setScaleMode(if (mode == ScreenOffController.PipelineMode.AUTO_MIRROR) {
            ScaleMode.FIT
        } else {
            requestedScaleMode
        })
        true
    }

    fun setScaleMode(mode: ScaleMode) = runLocked {
        requestedScaleMode = mode
        if (isSelfDrawnLocked()) {
            DisplayTransform.setScaleMode(mode)
        } else {
            // AUTO_MIRROR is an OS-owned FIT renderer. Keep input aligned with actual output until
            // the self-drawn renderer is selected; the requested value remains observable.
            if (mode != ScaleMode.FIT) {
                StructuredLog.w("MIRROR", "Scale $mode requested; AUTO_MIRROR supports FIT only")
            }
            DisplayTransform.setScaleMode(ScaleMode.FIT)
        }
    }

    fun setRotationMode(mode: RotationMode) = runLocked {
        rotationMode = mode
        DisplayTransform.setRotationMode(mode)
    }

    /** Compatibility entry point for the existing AutoMirrorEngine. */
    fun attachProjection(mediaProjection: MediaProjection): Boolean =
        attachProjection(context = null, mediaProjection = mediaProjection)

    /** Starts a consented session and selects the configured renderer exactly once. */
    fun attachProjection(context: Context?, mediaProjection: MediaProjection): Boolean = runLockedResult {
        stopRendererLocked()
        projection?.let { oldProjection -> runCatching { oldProjection.stop() } }
        projection = mediaProjection
        if (!canRenderLocked()) {
            StructuredLog.w(TAG, "Projection rejected because mirror policy is not PARKED/available")
            return@runLockedResult failProjectionLocked()
        }
        if (ScreenOffController.pipelineMode == ScreenOffController.PipelineMode.OWN_CONTENT) {
            StructuredLog.w(TAG, "OWN_CONTENT needs a dedicated app display; refusing unavailable pipeline")
            MirrorDiagnostics.record("own_content_unavailable")
            return@runLockedResult failProjectionLocked()
        }

        DisplayTransform.setScaleMode(
            if (isSelfDrawnLocked()) requestedScaleMode else ScaleMode.FIT
        )
        DisplayTransform.setRotationMode(rotationMode)
        ReconnectTracker.reset()
        MirrorDiagnostics.resetFrameStats()
        MirrorDiagnostics.record("projection_attached")

        val started = if (isSelfDrawnLocked()) {
            val appContext = context?.applicationContext
            if (appContext == null) {
                StructuredLog.e(TAG, "SELF_DRAWN requires a Context to create ImageReader")
                false
            } else {
                // Defer ImageReader/VirtualDisplay creation until a valid car surface exists. The
                // projection token can remain alive for reconnect without consuming frames into a
                // reader that has no output target.
                selfDrawnEngine = SelfDrawnMirrorEngine(appContext)
                true
            }
        } else {
            true
        }
        if (!started) return@runLockedResult failProjectionLocked()

        val attached = reconcileLocked()
        if (!attached) return@runLockedResult failProjectionLocked()
        true
    }

    fun attachCarSurface(surface: Surface, width: Int, height: Int, dpi: Int): Boolean = runLockedResult {
        val oldSurface = carSurface
        if (oldSurface == null || oldSurface !== surface) {
            if (oldSurface != null) ReconnectTracker.onSurfaceDetached()
            when (ReconnectTracker.onSurfaceArrived(projectionAlive = projection != null)) {
                ReconnectTracker.Outcome.RESUMED -> MirrorDiagnostics.record("car_session_reconnected")
                ReconnectTracker.Outcome.FRESH_START,
                ReconnectTracker.Outcome.UNCHANGED -> Unit
            }
        }
        if (oldSurface != null && oldSurface !== surface) {
            detachOutputLocked(oldSurface)
            runCatching { oldSurface.release() }
        }
        carSurface = surface
        carWidth = width
        carHeight = height
        carDpi = dpi
        MirrorDiagnostics.record("car_surface_attached")
        reconcileLocked()
    }

    fun detachCarSurface(surface: Surface?) = runLocked {
        val current = carSurface
        if (current != null && (surface == null || current === surface)) {
            detachOutputLocked(current)
            runCatching { current.release() }
            carSurface = null
            ReconnectTracker.onSurfaceDetached()
            MirrorDiagnostics.record("car_surface_detached")
        }
    }

    fun stopProjection() = runLocked {
        stopRendererLocked()
        MirrorDiagnostics.resetFrameStats()
        val old = projection
        projection = null
        DisplayTransform.resetSurfaceState()
        runCatching { old?.stop() }
        // Only a real teardown is an event. The parking listener and the service call this
        // defensively even when nothing was ever attached, and recording those made the ring read
        // as if a live mirror session had died.
        if (old != null) MirrorDiagnostics.record("projection_stopped")
    }

    private fun reconcileLocked(): Boolean {
        val currentProjection = projection ?: return true
        val surface = carSurface

        if (!canRenderLocked() || surface == null || !surface.isValid) {
            detachOutputLocked(surface)
            return true
        }

        if (carWidth <= 0 || carHeight <= 0) return false
        val dpi = carDpi.takeIf { it > 0 } ?: SurfaceProfile.active.fallbackDpi
        if (isSelfDrawnLocked()) {
            val engine = selfDrawnEngine ?: return false
            if (!engine.isRunning() && !engine.startBlocking(currentProjection)) return false
            return engine.attachSurfaceBlocking(surface, carWidth, carHeight, dpi)
        }
        if (ScreenOffController.pipelineMode != ScreenOffController.PipelineMode.AUTO_MIRROR) {
            return false
        }

        val existing = virtualDisplay
        if (existing == null) {
            return createAutoMirrorDisplayLocked(currentProjection, surface, dpi)
        } else {
            Log.i(TAG, "Updating car surface ${carWidth}x${carHeight} @ ${dpi}dpi")
            val resized = runCatching { existing.resize(carWidth, carHeight, dpi) }
                .onSuccess { MirrorDiagnostics.record("virtual_display_resized") }
                .onFailure { Log.w(TAG, "resize failed", it) }
                .isSuccess
            val surfaceUpdated = if (resized) {
                runCatching { existing.surface = surface }
                    .onFailure { Log.w(TAG, "setSurface failed", it) }
                    .isSuccess
            } else {
                false
            }
            if (!resized || !surfaceUpdated) {
                Log.w(TAG, "Existing virtual display update failed; recreating output while keeping consent")
                runCatching { existing.release() }
                virtualDisplay = null
                return createAutoMirrorDisplayLocked(currentProjection, surface, dpi)
            }
        }
        return true
    }

    private fun createAutoMirrorDisplayLocked(
        currentProjection: MediaProjection,
        surface: Surface,
        dpi: Int
    ): Boolean {
        Log.i(TAG, "Creating car virtual display ${carWidth}x${carHeight} @ ${dpi}dpi")
        return try {
            virtualDisplay = currentProjection.createVirtualDisplay(
                "AutoBridgeMirror",
                carWidth,
                carHeight,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
            ) ?: error("No virtual display returned")
            MirrorDiagnostics.record("virtual_display_created")
            true
        } catch (error: RuntimeException) {
            Log.e(TAG, "Display creation failed; projection remains available for retry", error)
            runCatching { virtualDisplay?.release() }
            virtualDisplay = null
            false
        }
    }

    private fun detachOutputLocked(surface: Surface?) {
        if (isSelfDrawnLocked()) {
            selfDrawnEngine?.detachSurfaceBlocking(surface)
            selfDrawnEngine?.stopBlocking()
        } else {
            runCatching { virtualDisplay?.surface = null }
        }
    }

    private fun stopRendererLocked() {
        runCatching { selfDrawnEngine?.stopBlocking() }
        selfDrawnEngine = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
    }

    private fun failProjectionLocked(): Boolean {
        stopRendererLocked()
        val failedProjection = projection
        projection = null
        DisplayTransform.resetSurfaceState()
        runCatching { failedProjection?.stop() }
        return false
    }
}
