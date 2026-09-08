package dev.autobridge.input

import dev.autobridge.core.model.Insets
import dev.autobridge.core.model.RotationMode

/**
 * Compatibility facade for coordinate mapping. All production mapping now resolves through
 * DisplayTransform so rendering geometry, visible-area offsets, and touch use one transform.
 */
object CoordinateMapper {
    data class Point(val x: Float, val y: Float)

    /** Maps a point from a centered FIT car viewport to raw phone display pixels. */
    fun mapFit(
        carX: Float,
        carY: Float,
        carWidth: Int,
        carHeight: Int,
        phoneWidth: Int,
        phoneHeight: Int
    ): Point? = DisplayTransform.resolve(
        carWidth = carWidth,
        carHeight = carHeight,
        phoneWidth = phoneWidth,
        phoneHeight = phoneHeight,
        profile = DisplayProfile.FIT,
        rotationMode = RotationMode.PHONE,
        safeInsets = Insets.ZERO,
        visibleBounds = null,
        touchOffsetX = 0f,
        touchOffsetY = 0f
    )?.mapPoint(carX, carY)

    fun map(info: DisplayTransformInfo, carX: Float, carY: Float): Point? =
        info.mapPoint(carX, carY)

    fun debugMap(info: DisplayTransformInfo, carX: Float, carY: Float): DebugCoordinates? =
        info.debugMap(carX, carY)
}
