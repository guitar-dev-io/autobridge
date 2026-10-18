package dev.autobridge.desktop.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.autobridge.desktop.R
import dev.autobridge.desktop.display.FrameRenderer

/**
 * The floating control rail on the left edge, drawn on top of the page on both the car surface
 * and the phone: touch mode, mouse mode, keyboard, settings. The selected input mode is filled
 * blue. It floats like the reference design: the page keeps the whole screen.
 *
 * Sizes follow the target's density so the buttons are the same physical size on a 720p car
 * screen, a 1080p one and a phone.
 *
 * It hides itself [AUTO_HIDE_MS] after the last touch, so a video or a page is not covered for the
 * whole drive; any tap brings it back ([wake]), and a tap on its strip while it is hidden only
 * brings it back ([inZone]) instead of also clicking the page underneath. [onAutoHide] lets the
 * owner redraw once when it goes, since a still page sends no new frame by itself.
 */
class ControlRail(context: Context, @Volatile var density: Float) : FrameRenderer.Overlay {
    enum class Button { TOUCH, MOUSE, KEYBOARD, SETTINGS }

    @Volatile var mode: PointerMode = PointerMode.TOUCH
    /** The owner's gate (parked, a session to control); auto-hide works within it. */
    @Volatile var visible = true

    /** Called on the main thread when the rail hides itself, to redraw without it. */
    @Volatile var onAutoHide: (() -> Unit)? = null

    @Volatile private var shownUntilMs = SystemClock.uptimeMillis() + AUTO_HIDE_MS
    private val handler = Handler(Looper.getMainLooper())
    private val hide = Runnable { onAutoHide?.invoke() }

    init {
        // Shown when the page first appears, then out of the way like after any other touch.
        wake()
    }

    /** Whether the rail is on screen now: allowed by its owner and not timed out. */
    val shown: Boolean get() = visible && SystemClock.uptimeMillis() < shownUntilMs

    /** Shows the rail (again) for [AUTO_HIDE_MS] from now. */
    fun wake() {
        shownUntilMs = SystemClock.uptimeMillis() + AUTO_HIDE_MS
        handler.removeCallbacks(hide)
        handler.postDelayed(hide, AUTO_HIDE_MS + 50)
    }

    /** Whether ([x], [y]) is on the strip the rail occupies, shown or not. */
    fun inZone(x: Float, @Suppress("UNUSED_PARAMETER") y: Float): Boolean = x <= (14 + 46 + 14) * density

    private val icons: Map<Button, Drawable> = mapOf(
        Button.TOUCH to icon(context, R.drawable.ic_rail_touch),
        Button.MOUSE to icon(context, R.drawable.ic_rail_mouse),
        Button.KEYBOARD to icon(context, R.drawable.ic_rail_keyboard),
        Button.SETTINGS to icon(context, R.drawable.ic_rail_settings),
    )
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    @Volatile private var rects: Map<Button, RectF> = emptyMap()

    private fun icon(context: Context, id: Int): Drawable = ContextCompat.getDrawable(context, id)!!.mutate()

    override fun draw(canvas: Canvas, width: Int, height: Int) {
        if (!shown) return
        val layout = layout(width, height)
        rects = layout
        for ((button, r) in layout) {
            val selected = (button == Button.TOUCH && mode == PointerMode.TOUCH) || (button == Button.MOUSE && mode == PointerMode.MOUSE)
            fill.color = if (selected) SELECTED else IDLE
            canvas.drawOval(r, fill)
            val pad = r.width() * 0.27f
            icons.getValue(button).apply {
                setBounds((r.left + pad).toInt(), (r.top + pad).toInt(), (r.right - pad).toInt(), (r.bottom - pad).toInt())
                draw(canvas)
            }
        }
    }

    /** The button under ([x], [y]) in target pixels, with a little slop around each circle. */
    fun hit(x: Float, y: Float): Button? {
        if (!shown) return null
        val slop = 6 * density
        return rects.entries.firstOrNull { (_, r) ->
            x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop
        }?.key
    }

    private fun layout(width: Int, height: Int): Map<Button, RectF> {
        // 46dp circles, 10dp apart, 14dp from the left edge, starting a quarter of the way down —
        // clear of the browser's tab strip and address bar at the top.
        val size = (46 * density).coerceAtMost(height / 5.2f)
        val gap = size * 0.22f
        val left = 14 * density
        val total = 4 * size + 3 * gap
        val top = ((height - total) / 2f).coerceAtLeast(height * 0.18f).coerceAtMost((height - total).coerceAtLeast(0f))
        return Button.entries.mapIndexed { i, b ->
            val y = top + i * (size + gap)
            b to RectF(left, y, left + size, y + size)
        }.toMap()
    }

    companion object {
        /** How long the rail stays after the last touch. */
        const val AUTO_HIDE_MS = 4_000L

        private val SELECTED = Color.rgb(0x1E, 0x88, 0xE5)
        private val IDLE = Color.argb(0xE6, 0x42, 0x42, 0x46)
    }
}

/** How touches on the page are turned into mouse input. */
enum class PointerMode {
    /** The finger is the pointer: tap clicks there, drag scrolls the page. */
    TOUCH,
    /** Trackpad: drag moves the cursor, tap clicks where the cursor is. For small targets. */
    MOUSE,
}
