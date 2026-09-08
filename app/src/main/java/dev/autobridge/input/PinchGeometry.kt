package dev.autobridge.input

import kotlin.math.min

/**
 * AndroidX Car App's [androidx.car.app.SurfaceCallback.onScale] is the only multi-touch-shaped
 * signal the host exposes: a single focus point plus a scale factor, already summarized from
 * whatever the driver did on the head-unit surface. There is no raw independent multi-touch here,
 * and none over Shizuku's `input` CLI either (no multi-pointer subcommand, and true evdev
 * injection needs root, not just Shizuku's shell UID). The practical increment is to turn that
 * one scale factor into a synthesized two-finger horizontal pinch so Accessibility's
 * dispatchGesture (which does support concurrent strokes) can actually perform it.
 */
object PinchGeometry {
    data class TwoFingerGesture(
        val aFromX: Float, val aFromY: Float, val aToX: Float, val aToY: Float,
        val bFromX: Float, val bFromY: Float, val bToX: Float, val bToY: Float
    )

    private const val BASE_RADIUS_FRACTION = 0.12f
    private const val MIN_RADIUS = 60f
    private const val MIN_SCALE = 0.2f
    private const val MAX_SCALE = 5f

    fun forPinch(
        centerX: Float,
        centerY: Float,
        scaleFactor: Float,
        phoneWidth: Int,
        phoneHeight: Int
    ): TwoFingerGesture? {
        if (!centerX.isFinite() || !centerY.isFinite() || !scaleFactor.isFinite()) return null
        if (phoneWidth <= 0 || phoneHeight <= 0) return null

        val shortSide = min(phoneWidth, phoneHeight).toFloat()
        val maxRadius = shortSide / 2f - 1f
        if (maxRadius <= 0f) return null

        val fromRadius = (shortSide * BASE_RADIUS_FRACTION).coerceIn(MIN_RADIUS, maxRadius)
        val clampedScale = scaleFactor.coerceIn(MIN_SCALE, MAX_SCALE)
        val toRadius = (fromRadius * clampedScale).coerceIn(1f, maxRadius)

        fun clampX(x: Float) = x.coerceIn(0f, phoneWidth - 1f)
        val y = centerY.coerceIn(0f, phoneHeight - 1f)

        return TwoFingerGesture(
            aFromX = clampX(centerX - fromRadius), aFromY = y,
            aToX = clampX(centerX - toRadius), aToY = y,
            bFromX = clampX(centerX + fromRadius), bFromY = y,
            bToX = clampX(centerX + toRadius), bToY = y
        )
    }
}
