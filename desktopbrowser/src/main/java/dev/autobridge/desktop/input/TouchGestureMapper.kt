package dev.autobridge.desktop.input

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.content.Context
import dev.autobridge.desktop.display.DisplayMapping
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Phone touch → desktop mouse, the way touch-first desktop clients do it:
 *
 * - tap: left click where the finger was;
 * - drag: scroll the page under the finger (content follows the finger), with a short fling;
 * - long press, then lift: right click (context menu);
 * - long press, then drag: left-button drag (select text, move a slider, drag a scrollbar);
 * - two fingers: pinch zooms the page (Ctrl+wheel), moving them scrolls.
 *
 * In [PointerMode.MOUSE] the screen is a trackpad instead: drag moves the cursor, tap clicks
 * where the cursor is, long press right-clicks there, two fingers still scroll. Double-tap and
 * hold, then drag, is a click-drag at the cursor (select text, drag a slider or scrollbar), the
 * same gesture a laptop trackpad uses.
 *
 * Coordinates go through the same [DisplayMapping] the renderer drew with, so the click lands on
 * the pixel under the finger.
 */
class TouchGestureMapper(
    context: Context,
    private val input: () -> BrowserInput?,
    private val mapping: () -> DisplayMapping?,
    private val mode: () -> PointerMode = { PointerMode.TOUCH },
    private val onInput: () -> Unit = {},
) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val doubleTapMs = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val doubleTapSlop = ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat()
    private var lastTapUpAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    /** Mouse mode: this touch came right after a tap, so moving it drags with the button held. */
    private var secondTap = false
    private val handler = Handler(Looper.getMainLooper())
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var longPressed = false
    private var dragging = false
    private var multi = false
    private var pinchStart = 0f
    private var pinchApplied = 1f
    private var velocity: VelocityTracker? = null

    private val longPress = Runnable {
        if (!moved && !multi) {
            longPressed = true
            if (mode() == PointerMode.MOUSE) return@Runnable
            val (dx, dy) = toDisplay(downX, downY) ?: return@Runnable
            input()?.moveTo(dx, dy)
        }
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        val target = input() ?: return true
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopFling()
                downX = event.x; downY = event.y; lastX = event.x; lastY = event.y
                moved = false; longPressed = false; dragging = false; multi = false
                secondTap = mode() == PointerMode.MOUSE && event.eventTime - lastTapUpAt < doubleTapMs &&
                    hypot(event.x - lastTapX, event.y - lastTapY) < doubleTapSlop
                handler.postDelayed(longPress, longPressMs)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) {
                multi = true
                handler.removeCallbacks(longPress)
                if (dragging && mode() == PointerMode.MOUSE) { target.releaseAtCursor(); dragging = false }
                pinchStart = spread(event)
                pinchApplied = 1f
                lastX = midX(event); lastY = midY(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (multi && event.pointerCount >= 2) {
                    val factor = spread(event) / pinchStart.coerceAtLeast(1f)
                    if (abs(factor / pinchApplied - 1f) > 0.12f) {
                        toDisplay(midX(event), midY(event))?.let { (dx, dy) -> target.zoom(dx, dy, factor / pinchApplied) }
                        pinchApplied = factor
                    } else {
                        scrollBy(target, midX(event), midY(event), lastX - midX(event), lastY - midY(event))
                    }
                    lastX = midX(event); lastY = midY(event)
                } else if (!multi) {
                    if (!moved && hypot(event.x - downX, event.y - downY) > slop) {
                        moved = true
                        handler.removeCallbacks(longPress)
                        if (longPressed && mode() == PointerMode.TOUCH) {
                            dragging = true
                            toDisplay(downX, downY)?.let { (dx, dy) -> target.press(dx, dy) }
                        } else if (secondTap && mode() == PointerMode.MOUSE) {
                            dragging = true
                            target.pressAtCursor()
                        }
                    }
                    if (moved && mode() == PointerMode.MOUSE) {
                        mapping()?.let { map ->
                            target.moveBy(map.toDisplayDistance(event.x - lastX), map.toDisplayDistance(event.y - lastY), map.displayWidth, map.displayHeight)
                        }
                    } else if (moved) {
                        if (dragging) {
                            toDisplay(event.x, event.y)?.let { (dx, dy) -> target.moveTo(dx, dy) }
                        } else {
                            scrollBy(target, event.x, event.y, lastX - event.x, lastY - event.y)
                        }
                    }
                    lastX = event.x; lastY = event.y
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                if (!multi && mode() == PointerMode.MOUSE) {
                    when {
                        dragging -> target.releaseAtCursor()
                        longPressed && !moved -> target.clickAtCursor(BrowserInput.BUTTON_RIGHT)
                        !moved -> {
                            target.clickAtCursor()
                            lastTapUpAt = event.eventTime; lastTapX = event.x; lastTapY = event.y
                        }
                    }
                } else if (!multi) {
                    when {
                        dragging -> toDisplay(event.x, event.y)?.let { (dx, dy) -> target.release(dx, dy) }
                        longPressed && !moved -> toDisplay(downX, downY)?.let { (dx, dy) -> target.click(dx, dy, BrowserInput.BUTTON_RIGHT) }
                        !moved -> toDisplay(downX, downY)?.let { (dx, dy) -> target.click(dx, dy) }
                        else -> {
                            velocity?.computeCurrentVelocity(1000)
                            fling(target, event.x, event.y, velocity?.xVelocity ?: 0f, velocity?.yVelocity ?: 0f)
                        }
                    }
                }
                dragging = false
                velocity?.recycle(); velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                if (dragging) {
                    if (mode() == PointerMode.MOUSE) target.releaseAtCursor()
                    else toDisplay(lastX, lastY)?.let { (dx, dy) -> target.release(dx, dy) }
                }
                dragging = false
                velocity?.recycle(); velocity = null
            }
        }
        onInput()
        return true
    }

    private var flingRunnable: Runnable? = null

    private fun fling(target: BrowserInput, x: Float, y: Float, vx: Float, vy: Float) {
        if (hypot(vx, vy) < 800f) return
        var remainingX = -vx * 0.18f
        var remainingY = -vy * 0.18f
        val step = object : Runnable {
            override fun run() {
                val sx = remainingX * 0.25f
                val sy = remainingY * 0.25f
                remainingX -= sx; remainingY -= sy
                scrollBy(target, x, y, sx, sy)
                onInput()
                if (hypot(remainingX, remainingY) > 20f) handler.postDelayed(this, 16) else flingRunnable = null
            }
        }
        flingRunnable = step
        handler.post(step)
    }

    private fun stopFling() {
        flingRunnable?.let(handler::removeCallbacks)
        flingRunnable = null
    }

    private fun scrollBy(target: BrowserInput, x: Float, y: Float, dxSurface: Float, dySurface: Float) {
        val map = mapping() ?: return
        val (ax, ay) = map.toDisplay(x, y)
        target.scroll(ax, ay, map.toDisplayDistance(dxSurface), map.toDisplayDistance(dySurface))
    }

    private fun toDisplay(x: Float, y: Float): Pair<Int, Int>? = mapping()?.toDisplay(x, y)

    private fun spread(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    private fun midX(e: MotionEvent) = (e.getX(0) + e.getX(1)) / 2f
    private fun midY(e: MotionEvent) = (e.getY(0) + e.getY(1)) / 2f
}
