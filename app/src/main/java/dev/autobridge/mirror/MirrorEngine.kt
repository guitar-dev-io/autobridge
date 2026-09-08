package dev.autobridge.mirror

import android.media.projection.MediaProjection
import android.view.Surface
import dev.autobridge.core.model.RotationMode
import dev.autobridge.core.model.ScaleMode

/** Request used by an engine implementation to begin a consented projection session. */
data class MirrorStartRequest(val projection: MediaProjection)

/**
 * Rendering lifecycle abstraction. The current AutoMirrorEngine keeps the low-latency OS-owned
 * path; a future own-content renderer can implement the same contract without changing car UI or
 * input coordination.
 */
interface MirrorEngine {
    val supportedScaleModes: Set<ScaleMode>
    val supportedRotationModes: Set<RotationMode>

    suspend fun start(request: MirrorStartRequest): Boolean
    suspend fun stop()
    suspend fun attachSurface(surface: Surface, width: Int, height: Int, dpi: Int): Boolean
    suspend fun detachSurface(surface: Surface? = null)
    fun setScaleMode(mode: ScaleMode)
    fun setRotationMode(mode: RotationMode)
}

/** Adapter around the existing consent-bound AUTO_MIRROR coordinator. */
class AutoMirrorEngine : MirrorEngine {
    override val supportedScaleModes: Set<ScaleMode> = setOf(ScaleMode.FIT)
    override val supportedRotationModes: Set<RotationMode> = setOf(
        RotationMode.AUTO,
        RotationMode.PHONE
    )

    override suspend fun start(request: MirrorStartRequest): Boolean {
        MirrorCoordinator.attachProjection(request.projection)
        return MirrorCoordinator.isProjectionReady
    }

    override suspend fun stop() {
        MirrorCoordinator.stopProjection()
    }

    override suspend fun attachSurface(surface: Surface, width: Int, height: Int, dpi: Int): Boolean {
        MirrorCoordinator.attachCarSurface(surface, width, height, dpi)
        return MirrorCoordinator.isCarSurfaceReady
    }

    override suspend fun detachSurface(surface: Surface?) {
        MirrorCoordinator.detachCarSurface(surface)
    }

    override fun setScaleMode(mode: ScaleMode) {
        MirrorCoordinator.setScaleMode(mode)
    }

    override fun setRotationMode(mode: RotationMode) {
        MirrorCoordinator.setRotationMode(mode)
    }
}

object DefaultMirrorEngine : MirrorEngine by AutoMirrorEngine()
