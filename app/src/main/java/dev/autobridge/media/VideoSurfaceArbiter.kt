package dev.autobridge.media

import android.os.Handler
import android.os.Looper

/**
 * Decides who owns the shared player's single video output: the car or the phone.
 *
 * Phone and car each talk to [MediaPlaybackService] through their own MediaController, but there
 * is one ExoPlayer behind them and it has exactly one video surface. Whoever sets a surface last
 * wins, and whoever clears last blanks everyone. That is how "send to car, then go back on the
 * phone" left the head unit with sound and a frozen picture: the phone player clearing its own
 * view on pause cleared the car's output too.
 *
 * The rule here is simple: while a car screen is showing video, the car owns the output. The phone
 * drops its binding when the car claims, does not take it back while the car holds it, and is told
 * when the car lets go so it can reattach. Main thread only.
 */
object VideoSurfaceArbiter {
    /** Phone-side screens that bind a video view to the shared player. */
    interface PhoneOutput {
        /** The car just took the output; drop the phone binding now. */
        fun onCarClaimed()

        /** The car released the output; the phone may bind again if it is visible. */
        fun onCarReleased()
    }

    /**
     * The phone's clear travels through a different controller than the car's set, so their order
     * at the session is not guaranteed. The car re-asserts its surface after these delays, which
     * makes the car's binding the last word either way.
     */
    private val REASSERT_DELAYS_MS = longArrayOf(250L, 900L)

    private val main = Handler(Looper.getMainLooper())
    private val phones = linkedSetOf<PhoneOutput>()
    private var carReassert: (() -> Unit)? = null

    /** True while a car screen holds the video output. */
    val carActive: Boolean
        get() = carReassert != null

    /**
     * Called by a car screen right after it binds its surface. [reassert] re-binds that surface
     * and nudges a repaint; it is invoked shortly after, once any phone clear has landed.
     */
    fun claimForCar(reassert: () -> Unit) {
        val wasActive = carActive
        carReassert = reassert
        if (!wasActive) phones.toList().forEach { it.onCarClaimed() }
        REASSERT_DELAYS_MS.forEach { delay ->
            main.postDelayed({ if (carReassert === reassert) reassert() }, delay)
        }
    }

    /** Called by a car screen when it unbinds. Ignored if another car screen has claimed since. */
    fun releaseForCar(reassert: () -> Unit) {
        if (carReassert !== reassert) return
        carReassert = null
        phones.toList().forEach { it.onCarReleased() }
    }

    fun registerPhone(output: PhoneOutput) {
        phones += output
    }

    fun unregisterPhone(output: PhoneOutput) {
        phones -= output
    }
}
