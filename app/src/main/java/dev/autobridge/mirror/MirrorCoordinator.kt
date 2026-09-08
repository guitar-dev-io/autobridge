package dev.autobridge.mirror

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Surface
import dev.autobridge.core.model.Feature
import dev.autobridge.core.model.RotationMode
import dev.autobridge.core.model.ScaleMode
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.display.MirrorDiagnostics
import dev.autobridge.display.StructuredLog
import dev.autobridge.display.SurfaceProfile
import dev.autobridge.input.DisplayTransform
import dev.autobridge.safety.ParkingStateStore

/**
 * Joins the phone-side MediaProjection session to the Surface supplied by Android Auto.
 * Surface changes use VirtualDisplay.resize()/setSurface() and never recreate the projection.
 */
object MirrorCoordinator {
    private const val TAG = "AutoBridgeMirror"
    private val lock = Any()

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var carSurface: Surface? = null
    private var carWidth: Int = 0
    private var carHeight: Int = 0
    private var carDpi: Int = 0
    private var requestedScaleMode: ScaleMode = ScaleMode.FIT
    private var rotationMode: RotationMode = RotationMode.AUTO

    private val parkingListener: (ParkingStateStore.State) -> Unit = {
        runLocked { reconcileLocked() }
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

    private fun canRenderLocked(): Boolean =
        FeaturePolicy.app.isAvailable(Feature.MIRROR)

    private fun isMirroringLocked(): Boolean =
        projection != null && virtualDisplay != null && carSurface?.isValid == true && canRenderLocked()

    /** Runs [block] under [lock] and reports to diagnostics if mirroring changes. */
    private fun runLocked(block: () -> Unit) {
        synchronized(lock) {
            val before = isMirroringLocked()
            block()
            val after = isMirroringLocked()
            if (before != after) MirrorDiagnostics.onMirroringActiveChanged(after)
        }
    }

    fun setScaleMode(mode: ScaleMode) = runLocked {
        requestedScaleMode = mode
        // AUTO_MIRROR is an OS-owned FIT renderer. Keep input aligned with the actual output until
        // an own-content renderer is selected; the requested value remains observable in logs.
        if (mode != ScaleMode.FIT) {
            StructuredLog.w("MIRROR", "Scale $mode requested; AUTO_MIRROR supports FIT only")
        }
        DisplayTransform.setScaleMode(ScaleMode.FIT)
    }

    fun setRotationMode(mode: RotationMode) = runLocked {
        rotationMode = mode
        DisplayTransform.setRotationMode(mode)
    }

    fun attachProjection(mediaProjection: MediaProjection) = runLocked {
        projection?.let { oldProjection -> runCatching { oldProjection.stop() } }
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        projection = mediaProjection
        DisplayTransform.setScaleMode(ScaleMode.FIT)
        DisplayTransform.setRotationMode(rotationMode)
        // A freshly-consented projection starts a new session; reconnect counting restarts.
        ReconnectTracker.reset()
        MirrorDiagnostics.record("projection_attached")
        reconcileLocked()
    }

    fun attachCarSurface(surface: Surface, width: Int, height: Int, dpi: Int) = runLocked {
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
            runCatching { virtualDisplay?.surface = null }
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
            runCatching { virtualDisplay?.surface = null }
            runCatching { current.release() }
            carSurface = null
            ReconnectTracker.onSurfaceDetached()
            MirrorDiagnostics.record("car_surface_detached")
        }
    }

    fun stopProjection() = runLocked {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        val old = projection
        projection = null
        DisplayTransform.resetSurfaceState()
        runCatching { old?.stop() }
        MirrorDiagnostics.record("projection_stopped")
    }

    private fun reconcileLocked() {
        val currentProjection = projection ?: return
        val surface = carSurface

        if (!canRenderLocked() || surface == null || !surface.isValid) {
            runCatching { virtualDisplay?.surface = null }
            return
        }

        if (carWidth <= 0 || carHeight <= 0) return
        val dpi = carDpi.takeIf { it > 0 } ?: SurfaceProfile.active.fallbackDpi
        val existing = virtualDisplay
        if (existing == null) {
            Log.i(TAG, "Creating car virtual display ${carWidth}x${carHeight} @ ${dpi}dpi")
            try {
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
            } catch (error: RuntimeException) {
                Log.e(TAG, "Display creation failed; fresh consent required", error)
                stopProjection()
            }
        } else {
            Log.i(TAG, "Updating car surface ${carWidth}x${carHeight} @ ${dpi}dpi")
            runCatching { existing.resize(carWidth, carHeight, dpi) }
                .onSuccess { MirrorDiagnostics.record("virtual_display_resized") }
                .onFailure { Log.w(TAG, "resize failed", it) }
            runCatching { existing.surface = surface }
                .onFailure { Log.w(TAG, "setSurface failed", it) }
        }
    }
}
