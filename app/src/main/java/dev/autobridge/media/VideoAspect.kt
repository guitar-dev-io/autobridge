package dev.autobridge.media

import dev.autobridge.settings.AspectRatio

/**
 * The size to give the video view inside its box, for a chosen [AspectRatio].
 *
 * The phone player draws into a plain `SurfaceView` centred in a clipping frame, and a Surface is
 * filled by whatever is decoded into it regardless of the frame's shape. Sizing the *view* is
 * therefore what letterboxes, crops or stretches the picture, and that is pure arithmetic — kept
 * here so the cases that are easy to get wrong (an unknown source size, a zero-height box, a crop
 * that must overflow rather than fit) are covered without starting a player.
 *
 * A returned size larger than the box is deliberate: [AspectRatio.FILL] crops, and the clipping
 * parent is what hides the overflow.
 */
object VideoAspect {
    data class Size(val width: Int, val height: Int)

    fun layout(
        mode: AspectRatio,
        videoWidth: Int,
        videoHeight: Int,
        boxWidth: Int,
        boxHeight: Int
    ): Size {
        if (boxWidth <= 0 || boxHeight <= 0) return Size(boxWidth, boxHeight)
        if (mode == AspectRatio.STRETCH) return Size(boxWidth, boxHeight)

        val sourceRatio = if (videoWidth > 0 && videoHeight > 0) {
            videoWidth.toFloat() / videoHeight.toFloat()
        } else {
            0f
        }
        val target = when (mode) {
            AspectRatio.AUTO, AspectRatio.FILL -> sourceRatio
            AspectRatio.STRETCH -> 0f
            else -> mode.ratio
        }
        // Nothing has reported a frame size yet (the first seconds of a stream, or audio-only
        // content). Filling the box is the only honest answer; it is also what the view did
        // before any of this existed, so the picture does not jump when the size arrives.
        if (target <= 0f) return Size(boxWidth, boxHeight)

        val boxRatio = boxWidth.toFloat() / boxHeight.toFloat()
        val wider = target > boxRatio
        // Fit: the limiting edge touches the box. Fill: the other one does, and the rest spills.
        val matchWidth = if (mode == AspectRatio.FILL) !wider else wider
        return if (matchWidth) {
            Size(boxWidth, Math.round(boxWidth / target).coerceAtLeast(1))
        } else {
            Size(Math.round(boxHeight * target).coerceAtLeast(1), boxHeight)
        }
    }
}
