package dev.autobridge.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/**
 * A few bars that bounce like a sound-level meter, for whatever is playing. Purely decorative: the
 * motion is a set of sine waves at different speeds, not the audio's real levels.
 *
 * [isActive] is asked on every frame, so the bars follow play and pause without anyone telling
 * them. While it says no they rest low and still, and the view only checks again a few times a
 * second; it draws nothing and schedules nothing while detached.
 */
class EqBarsView(
    context: Context,
    private val color: Int,
    private val barCount: Int = 4,
    private val isActive: () -> Boolean
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@EqBarsView.color }
    private val rect = RectF()
    private var active = false
    private var attached = false

    private val frame = object : Runnable {
        override fun run() {
            if (!attached) return
            val now = isActive()
            if (now != active) active = now
            invalidate()
            // 60 fps while moving; while resting, look again a few times a second.
            postDelayed(this, if (active) FRAME_MS else IDLE_CHECK_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        post(frame)
    }

    override fun onDetachedFromWindow() {
        attached = false
        removeCallbacks(frame)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val gap = width * GAP_SHARE
        val barWidth = (width - gap * (barCount - 1)) / barCount
        val t = SystemClock.uptimeMillis() / 1000.0
        for (i in 0 until barCount) {
            val level = if (active) {
                0.5 + 0.5 * sin(2 * PI * (SPEEDS[i % SPEEDS.size] * t) + PHASES[i % PHASES.size])
            } else {
                0.0
            }
            val h = height * (REST + (1f - REST) * level.toFloat())
            val left = i * (barWidth + gap)
            rect.set(left, height - h, left + barWidth, height.toFloat())
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, paint)
        }
    }

    private companion object {
        const val FRAME_MS = 16L
        const val IDLE_CHECK_MS = 400L
        const val GAP_SHARE = 0.12f
        const val REST = 0.2f
        val SPEEDS = doubleArrayOf(1.7, 2.3, 1.3, 2.9)
        val PHASES = doubleArrayOf(0.0, 1.9, 3.7, 5.1)
    }
}
