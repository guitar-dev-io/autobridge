package dev.autobridge.duoscreen.phone

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import dev.autobridge.logging.StructuredLog
import dev.autobridge.duoscreen.DuoScreenController

/**
 * Development harness that runs a real Duo Screen session on the phone screen, standing in for the
 * car surface so the pipeline can be exercised without a head unit:
 *
 * ```
 * adb shell am start -n dev.autobridge/.duoscreen.DuoScreenSpikeActivity \
 *     --es packages com.miui.calculator,com.android.settings
 * adb logcat -d | grep -E "AutoBridgeDuo"
 * adb exec-out screencap -p > /tmp/panes.png
 * ```
 *
 * Touches on this activity are forwarded to [DuoScreenController] exactly as the car host's
 * SurfaceCallback would, so pane hit-testing and input routing are testable here too. Delete once
 * [dev.autobridge.duoscreen.car.DuoScreenScreen] can be driven on a head unit.
 */
class DuoScreenSpikeActivity : Activity() {
    private companion object {
        const val TAG = "AutoBridgeDuoSpikeUi"
        const val DISPLAY_DPI = 320
        val DEFAULT_PACKAGES = listOf<String?>("com.miui.calculator", "com.android.settings")
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var controller: DuoScreenController
    private lateinit var status: TextView
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = DuoScreenController(this)

        val surfaceView = SurfaceView(this)
        status = TextView(this).apply {
            setTextColor(Color.YELLOW)
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            textSize = 14f
            text = "waiting for surface"
        }

        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.DKGRAY)
                addView(
                    surfaceView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                addView(
                    status,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { gravity = Gravity.BOTTOM }
                )
            }
        )

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (!started) startSession(holder, width, height)
            }

            // Detach, not stop: the harness exercises the same resume path the car screen takes,
            // so a surface lost and regained here has to keep the panes alive too.
            override fun surfaceDestroyed(holder: SurfaceHolder) = detachSession()
        })
    }

    private fun startSession(holder: SurfaceHolder, width: Int, height: Int) {
        val packages = intent?.getStringExtra("packages")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: DEFAULT_PACKAGES
        started = controller.start(holder.surface, width, height, DISPLAY_DPI, packages)
        report(
            if (started) {
                "session ${width}x$height panes=${controller.panes.map { "${it.id}:${it.packageName}" }}"
            } else {
                "session FAILED to start"
            }
        )
    }

    /**
     * Stands in for the car host's SurfaceCallback, which reports exactly these four gestures.
     * Long press has no SurfaceCallback equivalent and is only the harness's way of reaching the
     * mode toggle that the car template puts in its action strip.
     */
    private val gestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(event: MotionEvent): Boolean {
                controller.onClick(event.x.toInt(), event.y.toInt())
                return true
            }

            override fun onScroll(
                down: MotionEvent?,
                event: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                // Passed through exactly as the car host reports it — the distance scrolled, not
                // the way the finger went. Turning that into a drag is the router's job, so the
                // harness and the head unit feed it the same numbers.
                controller.onScroll(distanceX.toInt(), distanceY.toInt())
                return true
            }

            override fun onFling(
                down: MotionEvent?,
                event: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                controller.onFling(velocityX.toInt(), velocityY.toInt())
                return true
            }

            override fun onLongPress(event: MotionEvent) {
                val mode = controller.toggleMode()
                report("mode=$mode")
            }

            /** Stands in for the car template's layout button, which has no gesture either. */
            override fun onDoubleTap(event: MotionEvent): Boolean {
                report("preset=${controller.cyclePreset()}")
                return true
            }
        })
    }

    private val scaleGestures by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                controller.onScale(
                    detector.focusX.toInt(),
                    detector.focusY.toInt(),
                    detector.scaleFactor
                )
                return true
            }
        })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!started) return super.onTouchEvent(event)
        scaleGestures.onTouchEvent(event)
        if (!scaleGestures.isInProgress) gestures.onTouchEvent(event)
        return true
    }

    private fun report(message: String) {
        StructuredLog.i(TAG, message)
        status.text = message
    }

    private fun detachSession() {
        if (!started) return
        started = false
        controller.detach()
        StructuredLog.i(TAG, "session detached")
    }

    private fun stopSession() {
        started = false
        // Real teardown: let stop() clear the Duo-active media gate (restart()'s internal stop() does not).
        controller.tearingDown = true
        controller.stop()
        StructuredLog.i(TAG, "session stopped")
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        stopSession()
        super.onDestroy()
    }
}
