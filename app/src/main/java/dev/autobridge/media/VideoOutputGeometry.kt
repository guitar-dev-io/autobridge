package dev.autobridge.media

import java.util.concurrent.CopyOnWriteArrayList

/**
 * The pixel size of the surface the shared player is currently drawing video into.
 *
 * ## Why this exists
 *
 * ExoPlayer scales a decoded frame to fill whatever Surface it is handed, ignoring the frame's own
 * aspect ratio. With a `SurfaceView` that is harmless, because `PlayerView` resizes the view to the
 * video's shape first — but the Android Auto surface arrives at a fixed size chosen by the head
 * unit, and a portrait unit hands out a near-square one. A 16:9 channel drawn into it comes out
 * stretched vertically, which is what the picture on the car screen showed.
 *
 * Correcting that means letterboxing inside the player, and the player needs the target size. That
 * size is known only to the car screen that received the Surface
 * ([dev.autobridge.car.CarVideoScreen]), while the ExoPlayer instance lives in
 * [MediaPlaybackService]; car screens hold a `MediaController`, which cannot reach the
 * ExoPlayer-only effect API. Both run in the same process, so this store carries the value across,
 * in the same shape as [dev.autobridge.safety.ParkingStateStore].
 *
 * The producer publishes *after* attaching the Surface, because the renderer rejects an output
 * resolution while it has no surface to apply it to.
 */
object VideoOutputGeometry {
    /** A surface size in pixels. */
    data class Output(val width: Int, val height: Int) {
        val aspectRatio: Float get() = width.toFloat() / height.toFloat()
    }

    private val listeners = CopyOnWriteArrayList<(Output?) -> Unit>()

    /** The current video output surface, or null when no screen has claimed one. */
    @Volatile
    var current: Output? = null
        private set

    fun set(width: Int, height: Int) {
        update(if (width > 0 && height > 0) Output(width, height) else null)
    }

    fun clear() = update(null)

    private fun update(value: Output?) {
        // Android Auto re-delivers the same container on reconnect; rebuilding the effect pipeline
        // for an unchanged size would stutter the picture for nothing.
        if (current == value) return
        current = value
        listeners.forEach { it(value) }
    }

    fun addListener(listener: (Output?) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (Output?) -> Unit) {
        listeners -= listener
    }
}
