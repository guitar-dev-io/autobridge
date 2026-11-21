package dev.autobridge.aapdirect

import android.content.Context
import android.os.SystemClock
import dev.autobridge.aap.AapKey
import dev.autobridge.aap.AapTouch
import dev.autobridge.input.InputCapability
import dev.autobridge.input.TouchRouter
import java.util.concurrent.Executors
import kotlin.math.hypot

/**
 * Touch from the car while it shows the whole phone screen. Other apps' windows can only be
 * touched through AutoBridge's input backends (Shizuku or the accessibility service), via
 * [TouchRouter], which also maps car coordinates onto the letterboxed phone image.
 *
 * With Shizuku's real-touch stream every pointer goes through as it moves. Otherwise each touch is
 * turned into a gesture when the finger lifts: a tap, a long press, or a swipe from where it went
 * down to where it came up.
 */
class MirrorTouch(private val context: Context, private val width: Int, private val height: Int) {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "aa-direct-mirror-touch") }
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downAt = 0L
    private var raw = false

    /** True when some backend can touch other apps; without one, touches do nothing. */
    val available: Boolean get() = TouchRouter.availableCapabilities().isNotEmpty()

    fun touch(event: AapTouch, touchWidth: Int, touchHeight: Int) {
        val sx = width.toFloat() / (if (touchWidth > 0) touchWidth else width)
        val sy = height.toFloat() / (if (touchHeight > 0) touchHeight else height)
        val points = event.pointers.map { Triple(it.id, it.x * sx, it.y * sy) }
        if (points.isEmpty()) return
        worker.execute { runCatching { handle(event, points) } }
    }

    fun key(key: AapKey) {
        if (key.down) return
        worker.execute {
            when (key.keycode) {
                android.view.KeyEvent.KEYCODE_BACK -> TouchRouter.back()
                android.view.KeyEvent.KEYCODE_HOME -> TouchRouter.home()
            }
        }
    }

    fun release() {
        worker.shutdownNow()
    }

    private fun handle(event: AapTouch, points: List<Triple<Int, Float, Float>>) {
        val (id, x, y) = points.getOrNull(event.actionIndex) ?: points.first()
        when (event.action) {
            AapTouch.PRESS -> {
                raw = InputCapability.REAL_TOUCH in TouchRouter.availableCapabilities() &&
                    TouchRouter.rawTouchDown(context, id, x, y, width, height)
                downX = x; downY = y; lastX = x; lastY = y
                downAt = SystemClock.uptimeMillis()
            }
            AapTouch.POINTER_DOWN -> if (raw) TouchRouter.rawTouchDown(context, id, x, y, width, height)
            AapTouch.DRAG -> {
                lastX = points.first().second; lastY = points.first().third
                if (raw) {
                    TouchRouter.rawTouchMove(
                        context,
                        points.map { it.first }.toIntArray(),
                        points.map { it.second }.toFloatArray(),
                        points.map { it.third }.toFloatArray(),
                        width, height,
                    )
                }
            }
            AapTouch.POINTER_UP -> if (raw) TouchRouter.rawTouchUp(context, id, x, y, width, height)
            AapTouch.RELEASE -> {
                if (raw) {
                    TouchRouter.rawTouchUp(context, id, x, y, width, height)
                    return
                }
                lastX = x; lastY = y
                val held = SystemClock.uptimeMillis() - downAt
                val moved = hypot(lastX - downX, lastY - downY)
                when {
                    moved < TAP_SLOP_PX && held >= LONG_PRESS_MS ->
                        TouchRouter.longPress(context, downX, downY, width, height)
                    moved < TAP_SLOP_PX -> TouchRouter.tap(context, downX, downY, width, height)
                    else -> TouchRouter.swipe(context, downX, downY, lastX, lastY, width, height, held)
                }
            }
        }
    }

    private companion object {
        const val TAP_SLOP_PX = 24f
        const val LONG_PRESS_MS = 500L
    }
}
