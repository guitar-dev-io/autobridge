package dev.autobridge.mirror

import android.view.Surface
import dev.autobridge.input.ContentBounds

/** Explicit lifecycle state for the Android Auto-provided surface. */
enum class SurfaceLifecycle {
    UNAVAILABLE,
    AVAILABLE,
    CHANGED,
    DESTROYED
}

data class SurfaceState(
    val lifecycle: SurfaceLifecycle = SurfaceLifecycle.UNAVAILABLE,
    val surface: Surface? = null,
    val width: Int = 0,
    val height: Int = 0,
    val dpi: Int = 0,
    val visibleBounds: ContentBounds? = null,
    val generation: Long = 0L
) {
    val isAvailable: Boolean
        get() = lifecycle == SurfaceLifecycle.AVAILABLE || lifecycle == SurfaceLifecycle.CHANGED
}
