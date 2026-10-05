package dev.autobridge.projection

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.apps.auto.sdk.CarActivity
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
 * The phone's screen on the car display over the projection route — and the only surface in this
 * app that can pass Android Auto's touches on as a real pointer stream.
 *
 * Why this exists beside [dev.autobridge.car.MirrorCarScreen], which mirrors the same phone: the
 * template route never sees a finger. `SurfaceCallback` reports gestures the host has already
 * interpreted — onClick, onScroll, onFling, onScale — so a tap there can only be replayed into the
 * phone as a synthesised tap and a pinch as a synthesised pinch, and a drag that starts as a scroll
 * can never become a long-press. This is a real Activity the projection SDK draws on the car
 * display, so its root view is handed ordinary MotionEvents with every pointer, its id and its
 * coordinates. That is what [TouchRouter.rawTouchDown] and the three calls beside it were written
 * against, and what nothing in the app could feed until now.
 *
 * The privileged sink still has to be switched on (Settings > Touch and input > Real touch
 * injection) and still needs Shizuku. When it is off, unavailable, or refuses the gesture, the
 * stream falls back to the same synthesised tap the template route sends, so this screen works
 * either way rather than going dead.
 *
 * Mirroring itself is started on the phone, as it always was: this activity only supplies a surface
 * to [MirrorCoordinator] and takes touches back.
 */
class ProjectionMirrorActivity : CarActivity() {
    private companion object {
        const val TAG = "PROJECTION_MIRROR"

        /** How often the overlay re-asks whether mirroring has started, while it is showing. */
        const val STATUS_POLL_MS = 1_000L
    }

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var root: FrameLayout? = null
    private var status: TextView? = null

    /**
     * Whether the gesture in flight is being delivered as raw pointers. Decided once, on the first
     * ACTION_DOWN: a gesture that starts synthetic must not turn raw halfway through, which would
     * leave the injector holding a pointer that was never pressed.
     */
    private var rawGesture = false

    private val statusTicker = object : Runnable {
        override fun run() {
            refreshStatus()
            if (status?.visibility == View.VISIBLE) root?.postDelayed(this, STATUS_POLL_MS)
        }
    }

    private val parkingListener: (ParkingStateStore.State) -> Unit = { refreshStatus() }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The host relays out its panels as navigation comes and goes; rebuilding for each of those
        // would drop the surface and restart the mirror.
        setIgnoreConfigChanges(-1)
        carUiController.statusBarController.hideAppHeader()
        carUiController.menuController.hideMenuButton()
        // Process-global, and the template mirror screen claims the same thing. Both routes cannot
        // be on screen at once, but the diagnostics row should name whoever actually holds it.
        MirrorSurfaceOwnership.claim(this)
        setContentView(buildLayout())
        ParkingStateStore.addListener(parkingListener)
        refreshStatus()
    }

    @SuppressLint("ClickableViewAccessibility") // A mirror surface has no click semantics to announce.
    private fun buildLayout(): View {
        val surface = SurfaceView(this).apply { holder.addCallback(holderCallback) }
        val message = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            visibility = View.GONE
        }
        status = message
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            // Clickable, because a view that does not accept ACTION_DOWN is not offered the rest of
            // the gesture - and the rest of the gesture is the whole point here.
            isClickable = true
            addView(
                surface,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                message,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            setOnTouchListener { _, event -> onCarTouch(event) }
        }
        root = frame
        return frame
    }

    private fun allowed(): Boolean =
        FeaturePolicy.app.isAvailable(Feature.MIRROR) &&
            SafetyEnforcement.gateParked(ParkingStateStore.isParked)

    /**
     * One car gesture, pointer by pointer.
     *
     * ACTION_DOWN decides for the whole gesture: if the raw sink takes the first pointer, every
     * later pointer, move and release goes the same way; if it does not, nothing is injected until
     * the finger lifts and that single lift becomes a tap. The synthetic fallback is deliberately
     * one tap and not a replayed drag - [TouchRouter.scroll] exists for that and is what the
     * template route uses, and guessing a drag from a pointer stream the sink rejected would send
     * the phone a gesture the driver never made.
     */
    private fun onCarTouch(event: MotionEvent): Boolean {
        if (!allowed() || surfaceWidth <= 0 || surfaceHeight <= 0) return false
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
                    TouchRouter.tap(this, event.x, event.y, surfaceWidth, surfaceHeight)
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
        this,
        event.getPointerId(index),
        event.getX(index),
        event.getY(index),
        surfaceWidth,
        surfaceHeight
    )

    private fun sendUp(event: MotionEvent, index: Int): Boolean = TouchRouter.rawTouchUp(
        this,
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
        return TouchRouter.rawTouchMove(this, ids, xs, ys, surfaceWidth, surfaceHeight)
    }

    /**
     * What the black surface means while nothing is being drawn on it. Polled rather than observed:
     * mirroring starts on the phone and [MirrorCoordinator] publishes no change signal this route
     * could subscribe to, and the poll stops as soon as the overlay is gone.
     */
    private fun refreshStatus() {
        val view = status ?: return
        val text = when {
            !FeaturePolicy.app.isAvailable(Feature.MIRROR) ->
                FeaturePolicy.app.denialMessage(Feature.MIRROR)
            !SafetyEnforcement.gateParked(ParkingStateStore.isParked) ->
                resources.getString(R.string.projection_mirror_parked_only)
            !MirrorCoordinator.isMirroring ->
                resources.getString(R.string.projection_mirror_waiting)
            else -> null
        }
        view.text = text.orEmpty()
        view.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        root?.removeCallbacks(statusTicker)
        root?.postDelayed(statusTicker, STATUS_POLL_MS)
    }

    override fun onPause() {
        root?.removeCallbacks(statusTicker)
        super.onPause()
    }

    override fun onDestroy() {
        root?.removeCallbacks(statusTicker)
        ParkingStateStore.removeListener(parkingListener)
        // A pointer left down would stay down on the phone for as long as the injector lives.
        if (rawGesture) TouchRouter.rawTouchCancel()
        rawGesture = false
        MirrorSurfaceOwnership.release(this)
        root = null
        status = null
        super.onDestroy()
    }
}
