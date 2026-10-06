package dev.autobridge.projection

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import dev.autobridge.R
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.input.TouchRouter
import dev.autobridge.logging.StructuredLog
import dev.autobridge.mirror.MirrorCoordinator
import dev.autobridge.mirror.MirrorSurfaceOwnership
import dev.autobridge.safety.ParkingStateStore
import dev.autobridge.safety.SafetyEnforcement

/**
 * The phone's screen on the car display, with Android Auto's touches passed back as a real
 * pointer stream — as a view any projection activity can show.
 *
 * It used to be all of [ProjectionMirrorActivity], which needed its own projection service and so
 * its own icon on Android Auto. A second projection service is what stopped Android Auto offering
 * Bridge Web its split screen beside Maps, so the mirror now also lives inside Bridge Web
 * ([ProjectionBrowserActivity]'s menu > Mirror phone screen) on the one service, and the activity
 * is a thin shell around this pane for when it is listed on its own.
 *
 * Why real touches: the template route's `SurfaceCallback` only reports gestures the host already
 * interpreted, so a tap there can only be replayed as a synthesised tap. A projection activity's
 * views get ordinary MotionEvents with every pointer, which is what [TouchRouter.rawTouchDown] and
 * the calls beside it take. The privileged sink still has to be on (Settings > Touch and input >
 * Real touch injection) and needs Shizuku; when it is not, a gesture falls back to one synthesised
 * tap, so the pane works either way. Mirroring itself is started on the phone; this only supplies a
 * surface to [MirrorCoordinator] and takes touches back.
 *
 * [start] when shown, [stop] when hidden or torn down: the pane claims the process-global mirror
 * surface ([MirrorSurfaceOwnership]) for as long as it is up.
 */
@SuppressLint("ViewConstructor")
class ProjectionMirrorPane(context: Context) : FrameLayout(context) {
    private companion object {
        const val TAG = "PROJECTION_MIRROR"

        /** How often the message re-asks whether mirroring has started, while it is showing. */
        const val STATUS_POLL_MS = 1_000L
    }

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var started = false

    /**
     * Whether the gesture in flight is being delivered as raw pointers. Decided once, on the first
     * ACTION_DOWN: a gesture that starts synthetic must not turn raw halfway through, which would
     * leave the injector holding a pointer that was never pressed.
     */
    private var rawGesture = false

    private val status = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 18f
        gravity = Gravity.CENTER
        setPadding(32, 32, 32, 32)
        visibility = View.GONE
    }

    private val statusTicker = object : Runnable {
        override fun run() {
            refreshStatus()
            if (started && status.visibility == View.VISIBLE) postDelayed(this, STATUS_POLL_MS)
        }
    }

    private val parkingListener: (ParkingStateStore.State) -> Unit = { post { refreshStatus() } }

    private val holderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = Unit

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            surfaceWidth = width
            surfaceHeight = height
            val attached = MirrorCoordinator.attachCarSurface(
                holder.surface, width, height, resources.displayMetrics.densityDpi
            )
            StructuredLog.i(TAG, "car surface ${width}x$height attached=$attached")
            refreshStatus()
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            // Dropped before the size, so a touch arriving in between is rejected rather than
            // mapped against a surface that is already gone.
            surfaceWidth = 0
            surfaceHeight = 0
            MirrorCoordinator.detachCarSurface(holder.surface)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        // Clickable, because a view that does not accept ACTION_DOWN is not offered the rest of the
        // gesture - and the rest of the gesture is the whole point here.
        isClickable = true
        addView(
            SurfaceView(context).apply { holder.addCallback(holderCallback) },
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        addView(status, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Takes the mirror surface and starts listening. Idempotent. */
    fun start() {
        if (started) return
        started = true
        // Process-global, and the template mirror screen claims the same thing. Both routes cannot
        // be on screen at once, but the diagnostics row should name whoever actually holds it.
        MirrorSurfaceOwnership.claim(this)
        ParkingStateStore.addListener(parkingListener)
        refreshStatus()
        removeCallbacks(statusTicker)
        postDelayed(statusTicker, STATUS_POLL_MS)
    }

    /** Gives the mirror surface back and lifts any pointer still held down. Idempotent. */
    fun stop() {
        if (!started) return
        started = false
        removeCallbacks(statusTicker)
        ParkingStateStore.removeListener(parkingListener)
        // A pointer left down would stay down on the phone for as long as the injector lives.
        if (rawGesture) TouchRouter.rawTouchCancel()
        rawGesture = false
        MirrorSurfaceOwnership.release(this)
    }

    private fun allowed(): Boolean =
        FeaturePolicy.app.isAvailable(Feature.MIRROR) &&
            SafetyEnforcement.gateParked(ParkingStateStore.isParked)

    @SuppressLint("ClickableViewAccessibility") // A mirror surface has no click semantics to announce.
    override fun onTouchEvent(event: MotionEvent): Boolean = onCarTouch(event) || super.onTouchEvent(event)

    /**
     * One car gesture, pointer by pointer.
     *
     * ACTION_DOWN decides for the whole gesture: if the raw sink takes the first pointer, every
     * later pointer, move and release goes the same way; if it does not, nothing is injected until
     * the finger lifts and that single lift becomes a tap. The synthetic fallback is deliberately
     * one tap and not a replayed drag: guessing a drag from a pointer stream the sink rejected
     * would send the phone a gesture the driver never made.
     */
    private fun onCarTouch(event: MotionEvent): Boolean {
        if (!started || !allowed() || surfaceWidth <= 0 || surfaceHeight <= 0) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                rawGesture = sendDown(event, event.actionIndex)
                true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (rawGesture) sendDown(event, event.actionIndex)
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (rawGesture) sendMove(event)
                true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (rawGesture) sendUp(event, event.actionIndex)
                true
            }

            MotionEvent.ACTION_UP -> {
                if (rawGesture) {
                    sendUp(event, event.actionIndex)
                } else {
                    TouchRouter.tap(context, event.x, event.y, surfaceWidth, surfaceHeight)
                }
                rawGesture = false
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (rawGesture) TouchRouter.rawTouchCancel()
                rawGesture = false
                true
            }

            else -> false
        }
    }

    private fun sendDown(event: MotionEvent, index: Int): Boolean = TouchRouter.rawTouchDown(
        context,
        event.getPointerId(index),
        event.getX(index),
        event.getY(index),
        surfaceWidth,
        surfaceHeight
    )

    private fun sendUp(event: MotionEvent, index: Int): Boolean = TouchRouter.rawTouchUp(
        context,
        event.getPointerId(index),
        event.getX(index),
        event.getY(index),
        surfaceWidth,
        surfaceHeight
    )

    /** Every pointer still down, in one call: a move is the whole hand or it is not a pinch. */
    private fun sendMove(event: MotionEvent): Boolean {
        val count = event.pointerCount
        val ids = IntArray(count) { event.getPointerId(it) }
        val xs = FloatArray(count) { event.getX(it) }
        val ys = FloatArray(count) { event.getY(it) }
        return TouchRouter.rawTouchMove(context, ids, xs, ys, surfaceWidth, surfaceHeight)
    }

    /**
     * What the black surface means while nothing is being drawn on it. Polled rather than observed:
     * mirroring starts on the phone and [MirrorCoordinator] publishes no change signal this route
     * could subscribe to, and the poll stops as soon as the message is gone.
     */
    fun refreshStatus() {
        val text = when {
            !FeaturePolicy.app.isAvailable(Feature.MIRROR) ->
                FeaturePolicy.app.denialMessage(Feature.MIRROR)
            !SafetyEnforcement.gateParked(ParkingStateStore.isParked) ->
                resources.getString(R.string.projection_mirror_parked_only)
            !MirrorCoordinator.isMirroring ->
                resources.getString(R.string.projection_mirror_waiting)
            else -> null
        }
        status.text = text.orEmpty()
        val wasHidden = status.visibility != View.VISIBLE
        status.visibility = if (text == null) View.GONE else View.VISIBLE
        if (started && wasHidden && text != null) {
            removeCallbacks(statusTicker)
            postDelayed(statusTicker, STATUS_POLL_MS)
        }
    }
}
