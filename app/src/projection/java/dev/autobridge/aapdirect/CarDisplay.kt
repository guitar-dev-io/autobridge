package dev.autobridge.aapdirect

import android.app.Activity
import android.app.ActivityOptions
import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import dev.autobridge.aap.AapKey
import dev.autobridge.aap.AapTouch
import java.lang.ref.WeakReference

/**
 * The car's screen as an Android display: a private virtual display owned by this app, rendering
 * into the encoder's input surface. AutoBridge's own screens are launched onto it (they declare
 * allowEmbedded in the sideload manifest, which an untrusted display requires), so the car shows
 * real AutoBridge activities rather than a copy of the phone screen.
 *
 * Touches from the car are delivered in-process to whichever AutoBridge activity is in front on
 * this display - no input-injection permission is involved. A dialog or popup window on top of
 * that activity does not get them yet.
 */
class CarDisplay(
    private val context: Context,
    width: Int,
    height: Int,
    dpi: Int,
    surface: Surface,
) {
    private val main = Handler(Looper.getMainLooper())
    private val display: VirtualDisplay
    private val width = width
    private val height = height
    private var front: WeakReference<Activity>? = null
    private var downTime = 0L
    private val app = context.applicationContext as Application

    val displayId: Int get() = display.display.displayId

    private val tracker = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (displayOf(activity) == displayId) front = WeakReference(activity)
        }
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) {
            if (front?.get() === activity) front = null
        }
    }

    init {
        val manager = context.getSystemService(DisplayManager::class.java)
        // Neither PUBLIC nor any mirroring flag: a private display that shows only what this app
        // puts on it.
        display = manager.createVirtualDisplay(
            "AutoBridge car",
            width, height, dpi, surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )
        app.registerActivityLifecycleCallbacks(tracker)
    }

    fun setDensity(dpi: Int) {
        display.resize(width, height, dpi)
    }

    /** Opens [intent] on the car screen, in a task of its own. */
    fun launch(intent: Intent): Boolean = runCatching {
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        context.startActivity(
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
            options.toBundle(),
        )
    }.onFailure { DirectLog.add("could not open ${intent.component?.shortClassName} on the car: ${it.message}") }.isSuccess

    fun touch(event: AapTouch, touchWidth: Int, touchHeight: Int) {
        main.post { dispatchTouch(event, touchWidth, touchHeight) }
    }

    fun key(key: AapKey) {
        main.post {
            val activity = front?.get() ?: return@post
            val now = SystemClock.uptimeMillis()
            val keycode = when (key.keycode) {
                // The car's own back button and the steering-wheel/rotary keys arrive as Android
                // keycodes already; HOME goes to AutoBridge's car home rather than the phone's.
                KeyEvent.KEYCODE_HOME -> { if (!key.down) launch(CarHomeActivity.intent(context)); return@post }
                else -> key.keycode
            }
            activity.dispatchKeyEvent(
                KeyEvent(now, now, if (key.down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, keycode, 0)
            )
        }
    }

    fun release() {
        app.unregisterActivityLifecycleCallbacks(tracker)
        // Close what runs on this display first, so nothing is left on a display that is gone.
        front?.get()?.let { if (!it.isFinishing) it.finishAndRemoveTask() }
        front = null
        runCatching { display.release() }
    }

    // Activity.getDisplay() is API 30; the window manager's display is the same one on 29.
    @Suppress("DEPRECATION")
    private fun displayOf(activity: Activity): Int = activity.windowManager.defaultDisplay.displayId

    private fun dispatchTouch(event: AapTouch, touchWidth: Int, touchHeight: Int) {
        val activity = front?.get() ?: return
        if (event.pointers.isEmpty()) return
        val sx = width.toFloat() / (if (touchWidth > 0) touchWidth else width)
        val sy = height.toFloat() / (if (touchHeight > 0) touchHeight else height)
        val now = SystemClock.uptimeMillis()
        val action = when (event.action) {
            AapTouch.PRESS -> { downTime = now; MotionEvent.ACTION_DOWN }
            AapTouch.RELEASE -> MotionEvent.ACTION_UP
            AapTouch.DRAG -> MotionEvent.ACTION_MOVE
            AapTouch.POINTER_DOWN -> MotionEvent.ACTION_POINTER_DOWN or (event.actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            AapTouch.POINTER_UP -> MotionEvent.ACTION_POINTER_UP or (event.actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            else -> return
        }
        val properties = event.pointers.map { p ->
            MotionEvent.PointerProperties().apply { id = p.id; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }.toTypedArray()
        val coords = event.pointers.map { p ->
            MotionEvent.PointerCoords().apply { x = p.x * sx; y = p.y * sy; pressure = 1f; size = 1f }
        }.toTypedArray()
        val motion = MotionEvent.obtain(
            if (downTime == 0L) now else downTime, now, action, properties.size, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            activity.dispatchTouchEvent(motion)
        } finally {
            motion.recycle()
        }
    }
}
