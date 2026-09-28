package dev.autobridge.mirror

import android.graphics.Rect
import android.util.Log
import androidx.car.app.SurfaceContainer
import dev.autobridge.core.model.Insets
import dev.autobridge.core.state.RuntimeContextStore
import dev.autobridge.input.ContentBounds
import dev.autobridge.input.DisplayTransform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns surface callback translation and geometry registration. It never recreates MediaProjection;
 * changes are forwarded to MirrorCoordinator, which resizes/rebinds the existing VirtualDisplay.
 */
class MirrorSurfaceController(
    private val insetsProvider: () -> Insets = {
        RuntimeContextStore.context.value.vehicleProfile?.safeInsets ?: Insets.ZERO
    },
    private val touchOffsetProvider: () -> Pair<Float, Float> = {
        val profile = RuntimeContextStore.context.value.vehicleProfile
        (profile?.touchOffsetX ?: 0f) to (profile?.touchOffsetY ?: 0f)
    }
) {
    private companion object {
        const val TAG = "AutoBridgeSurface"
    }

    private val _state = MutableStateFlow(SurfaceState())
    val state: StateFlow<SurfaceState> = _state.asStateFlow()

    fun onSurfaceAvailable(container: SurfaceContainer): Boolean {
        val surface = container.surface ?: return false
        val old = _state.value
        val changed = old.isAvailable &&
            (old.surface !== surface || old.width != container.width || old.height != container.height || old.dpi != container.dpi)
        val next = SurfaceState(
            lifecycle = if (changed) SurfaceLifecycle.CHANGED else SurfaceLifecycle.AVAILABLE,
            surface = surface,
            width = container.width,
            height = container.height,
            dpi = container.dpi,
            visibleBounds = old.visibleBounds,
            generation = old.generation + 1
        )
        _state.value = next
        configureTransform(next)
        Log.i(TAG, "surface ${next.lifecycle} ${next.width}x${next.height} dpi=${next.dpi}")
        return MirrorCoordinator.attachCarSurface(surface, container.width, container.height, container.dpi)
    }

    fun onSurfaceDestroyed(container: SurfaceContainer): Boolean {
        val current = _state.value
        val destroyedSurface = container.surface
        if (destroyedSurface == null) {
            // Android Auto emits a destroy without a surface handle when the driver switches apps.
            // Detach the currently-tracked surface so the projection can rebind cleanly on the next
            // onSurfaceAvailable, instead of leaving a stale surface attached that never renders.
            if (current.surface == null) {
                Log.w(TAG, "Ignoring surface destroy without an identity")
                return false
            }
            Log.i(TAG, "Surface destroy without identity; detaching tracked surface for rebind")
            MirrorCoordinator.detachCarSurface(current.surface)
            DisplayTransform.resetSurfaceState()
            _state.value = current.copy(
                lifecycle = SurfaceLifecycle.DESTROYED,
                surface = null,
                width = 0,
                height = 0,
                dpi = 0,
                visibleBounds = null,
                generation = current.generation + 1
            )
            return true
        }
        if (current.surface !== destroyedSurface) {
            Log.w(TAG, "Ignoring stale surface destroy callback")
            return false
        }
        MirrorCoordinator.detachCarSurface(destroyedSurface)
        DisplayTransform.resetSurfaceState()
        _state.value = current.copy(
            lifecycle = SurfaceLifecycle.DESTROYED,
            surface = null,
            width = 0,
            height = 0,
            dpi = 0,
            visibleBounds = null,
            generation = current.generation + 1
        )
        Log.i(TAG, "surface destroyed generation=${_state.value.generation}")
        return true
    }

    fun clear() {
        val current = _state.value
        MirrorCoordinator.detachCarSurface(null)
        DisplayTransform.resetSurfaceState()
        _state.value = current.copy(
            lifecycle = SurfaceLifecycle.UNAVAILABLE,
            surface = null,
            width = 0,
            height = 0,
            dpi = 0,
            visibleBounds = null,
            generation = current.generation + 1
        )
    }

    fun onVisibleAreaChanged(rect: Rect) {
        val bounds = ContentBounds.fromRect(rect)
        DisplayTransform.setVisibleBounds(bounds)
        _state.value = _state.value.copy(visibleBounds = bounds)
        Log.i(TAG, "visible area $rect")
    }

    private fun configureTransform(state: SurfaceState) {
        val insets = insetsProvider()
        val offsets = touchOffsetProvider()
        DisplayTransform.setSafeInsets(insets)
        DisplayTransform.setTouchOffsets(offsets.first, offsets.second)
        DisplayTransform.setVisibleBounds(state.visibleBounds)
    }
}

/** Backward-compatible name for callers that think in terms of car-surface ownership. */
class CarSurfaceManager(
    insetsProvider: () -> Insets = {
        RuntimeContextStore.context.value.vehicleProfile?.safeInsets ?: Insets.ZERO
    },
    touchOffsetProvider: () -> Pair<Float, Float> = {
        val profile = RuntimeContextStore.context.value.vehicleProfile
        (profile?.touchOffsetX ?: 0f) to (profile?.touchOffsetY ?: 0f)
    }
) {
    private val controller = MirrorSurfaceController(insetsProvider, touchOffsetProvider)
    val state: StateFlow<SurfaceState> = controller.state

    fun onSurfaceAvailable(container: SurfaceContainer) = controller.onSurfaceAvailable(container)
    fun onSurfaceDestroyed(container: SurfaceContainer) = controller.onSurfaceDestroyed(container)
    fun clear() = controller.clear()
    fun onVisibleAreaChanged(rect: Rect) = controller.onVisibleAreaChanged(rect)
}
