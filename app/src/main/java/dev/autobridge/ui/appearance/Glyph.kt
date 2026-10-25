package dev.autobridge.ui.appearance

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import androidx.core.graphics.PathParser

/**
 * The line icons the Appearance pages use, as 24x24 SVG path data copied from the redesign
 * prototype, so the page draws the same glyphs the design canvas does without a drawable per icon.
 */
object Glyphs {
    const val PHONE = "M7 3h10v18H7zM11 18h2"
    const val SUN = "M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"
    const val MOON = "M20 14.5A8 8 0 0 1 9.5 4A8 8 0 1 0 20 14.5z"
    const val AMOLED = "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18M12 3v18M12 7h4M12 11h5M12 15h4"
    const val CLOCK = "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18M12 7v5l3 2"
    const val CHECK = "M5 12.5l4.5 4.5L19 7.5"
    const val UPLOAD = "M12 16V4M7 9l5-5l5 5M4 16v4h16v-4"
    const val CHEVRON = "M9 5l7 7l-7 7"
    const val PALETTE = "M12 3a9 9 0 1 0 0 18c1.1 0 1.5-.8 1.5-1.5c0-1-.8-1.4-.8-2.3c0-.9.7-1.7 1.7-1.7H17a4 4 0 0 0 4-4c0-4.7-4-8.5-9-8.5M7.5 11.5h.01M10 7.5h.01M15 7.5h.01"
    const val FONT = "M3 19l5-14l5 14M5 14h6M17 12.5a2.5 2.5 0 1 1 0 5a2.5 2.5 0 0 1 0-5M19.5 11v8"
    const val IMAGE = "M4 5h16v14H4zM4 16l5-5l4 4l3-3l4 4M15 9.5h.01"
    const val SHAPES = "M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM16.5 13a3.5 3.5 0 1 0 0 7a3.5 3.5 0 1 0 0-7"
    const val BROWSER = "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18M3 12h18M12 3c2.5 2.6 3.8 5.6 3.8 9s-1.3 6.4-3.8 9c-2.5-2.6-3.8-5.6-3.8-9S9.5 5.6 12 3"
    const val MUSIC = "M9 18V5l11-2v13M9 18a3 3 0 1 1-6 0a3 3 0 0 1 6 0M20 16a3 3 0 1 1-6 0a3 3 0 0 1 6 0"
    const val RADIO = "M4 9h16v11H4zM8 9l9-5M6.5 14.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0M13 13h4M13 17h4"
    const val TV = "M3 6h18v12H3zM8 21h8M12 18v3"
    const val MIRROR = "M3 7V6a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-5M3 11a9 9 0 0 1 9 9M3 15a5 5 0 0 1 5 5M3 19.5h.01"
    const val MY_CAR = "M5 16h14M3 16v-3l2-5a2 2 0 0 1 1.9-1.4h10.2A2 2 0 0 1 19 8l2 5v3M5 16v2.5M19 16v2.5M7 12.5h.01M17 12.5h.01M3 13h18"
}

/** One stroked [Glyphs] icon, scaled from its 24-unit box to the view's size. */
class GlyphView(
    context: Context,
    pathData: String,
    color: Int,
    /** Stroke width in the icon's own 24-unit space, as the prototype's stroke-width. */
    private val strokeUnits: Float = 1.8f
) : View(context) {
    private val source: Path = PathParser.createPathFromPathData(pathData)
    private val scaled = Path()
    private val matrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val side = minOf(width, height).toFloat()
        if (side <= 0f) return
        val scale = side / 24f
        matrix.setScale(scale, scale)
        matrix.postTranslate((width - side) / 2f, (height - side) / 2f)
        source.transform(matrix, scaled)
        paint.strokeWidth = strokeUnits * scale
        canvas.drawPath(scaled, paint)
    }
}
