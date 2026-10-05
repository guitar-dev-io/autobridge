package dev.autobridge.ui

import android.app.Activity
import android.os.Build
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/**
 * The system Back button, on the Android versions where overriding `Activity.onBackPressed()` no
 * longer does anything.
 *
 * Predictive back is enabled by default for apps targeting API 35 and up, and this app targets 36.
 * On such a device **a back gesture or button never calls `onBackPressed()`** — the system finishes
 * the Activity instead. Every screen here that does something of its own with Back lost that
 * behaviour silently and identically: one Back left the whole screen. On an Android 16 phone the
 * library's page stack jumped out to the home grid from three levels deep, the browser stopped
 * going back through its own history, and leaving fullscreen closed the player.
 *
 * These screens extend the framework [Activity], not androidx's `ComponentActivity`, so androidx's
 * `OnBackPressedDispatcher` is not available to them; the platform's [OnBackInvokedDispatcher] is.
 *
 * Exactly one of the two routes runs on any device, which is why both stay:
 *
 * | Device | Route |
 * |---|---|
 * | API < 33 | the deprecated `onBackPressed()` override |
 * | API 33–34 | the same override — the app does not set `android:enableOnBackInvokedCallback`, so the platform keeps dispatching the legacy way and never calls a registered callback |
 * | API 35+ | the callback registered here |
 *
 * A registered callback replaces the default behaviour completely, so a handler is responsible for
 * finishing the Activity itself once it has nothing of its own left to do — [finishFromBack] is
 * what the platform's own `onBackPressed()` would have done.
 */
object SystemBack {
    /**
     * Registers [handler] as this Activity's Back action where the platform supports it.
     *
     * Returns the function that unregisters it again; it is safe to call on any version and does
     * nothing where nothing was registered. Callers keep it and invoke it from `onDestroy`.
     */
    fun register(activity: Activity, handler: () -> Unit): () -> Unit {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return {}
        val callback = OnBackInvokedCallback { handler() }
        activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback
        )
        return { activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback) }
    }

    /**
     * Leaves the screen the way Back itself would have: with the exit transition, which plain
     * `finish()` skips. This is what `Activity.onBackPressed()` ends in once there is no action
     * bar to collapse and no fragment to pop — neither of which this app uses.
     */
    fun finishFromBack(activity: Activity) = activity.finishAfterTransition()
}
