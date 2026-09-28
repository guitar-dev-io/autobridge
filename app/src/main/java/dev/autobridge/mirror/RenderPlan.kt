package dev.autobridge.mirror

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import dev.autobridge.core.model.Insets
import dev.autobridge.input.DisplayTransform
import dev.autobridge.input.DisplayTransformInfo

/**
 * Immutable per-frame geometry for the self-drawn renderer.
 *
 * The same [DisplayTransformInfo] is used by [dev.autobridge.input.TouchRouter], so FIT bars,
 * FILL cropping, STRETCH distortion, ONE_TO_ONE placement, and quarter-turn rotation cannot drift
 * between the pixels drawn to the car and the coordinates sent back to the phone.
 */
data class RenderPlan(
    val surfaceGeneration: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val transform: DisplayTransformInfo,
    /** Corner radius, in car-surface pixels, of the [transform] viewport's card clip. 0 = square. */
    val cardCornerRadiusPx: Float = 0f
) {
    val isValid: Boolean
        get() = sourceWidth > 0 && sourceHeight > 0 && outputWidth > 0 && outputHeight > 0 &&
            transform.isValid && transform.phoneWidth == sourceWidth && transform.phoneHeight == sourceHeight

    /** Draws one source bitmap. The caller owns the Canvas lock/unlock lifecycle. */
    fun draw(canvas: Canvas, bitmap: Bitmap, paint: Paint) {
        canvas.drawColor(Color.BLACK)
        if (!isValid || bitmap.width != sourceWidth || bitmap.height != sourceHeight) return

        val bounds = transform.renderedBounds
        val scaleX = transform.scaleX
        val scaleY = transform.scaleY
        val sourceW = sourceWidth.toFloat()
        val sourceH = sourceHeight.toFloat()
        val matrix = Matrix()
        val values = when (transform.rotationQuarterTurns.mod(4)) {
            0 -> floatArrayOf(
                scaleX, 0f, bounds.left,
                0f, scaleY, bounds.top,
                0f, 0f, 1f
            )
            1 -> floatArrayOf(
                0f, -scaleX, bounds.left + sourceH * scaleX,
                scaleY, 0f, bounds.top,
                0f, 0f, 1f
            )
            2 -> floatArrayOf(
                -scaleX, 0f, bounds.left + sourceW * scaleX,
                0f, -scaleY, bounds.top + sourceH * scaleY,
                0f, 0f, 1f
            )
            else -> floatArrayOf(
                0f, scaleX, bounds.left,
                -scaleY, 0f, bounds.top + sourceW * scaleY,
                0f, 0f, 1f
            )
        }
        matrix.setValues(values)

        val saveCount = canvas.save()
        val viewport = transform.viewportBounds
        if (cardCornerRadiusPx > 0f) {
            val path = Path().apply {
                addRoundRect(
                    RectF(viewport.left, viewport.top, viewport.right, viewport.bottom),
                    cardCornerRadiusPx, cardCornerRadiusPx,
                    Path.Direction.CW
                )
            }
            canvas.clipPath(path)
        } else {
            canvas.clipRect(viewport.left, viewport.top, viewport.right, viewport.bottom)
        }
        canvas.drawBitmap(bitmap, matrix, paint)
        canvas.restoreToCount(saveCount)
    }

    companion object {
        fun resolve(
            surfaceGeneration: Long,
            outputWidth: Int,
            outputHeight: Int,
            sourceWidth: Int,
            sourceHeight: Int,
            cardMargin: Insets = DisplayTransform.cardMargin,
            cardCornerRadiusPx: Float = 0f
        ): RenderPlan? {
            val transform = DisplayTransform.resolve(
                carWidth = outputWidth,
                carHeight = outputHeight,
                phoneWidth = sourceWidth,
                phoneHeight = sourceHeight,
                cardMargin = cardMargin
            ) ?: return null
            return RenderPlan(
                surfaceGeneration = surfaceGeneration,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                transform = transform,
                cardCornerRadiusPx = cardCornerRadiusPx
            ).takeIf { it.isValid }
        }
    }
}
