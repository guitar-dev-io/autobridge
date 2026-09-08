package dev.autobridge.display

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.autobridge.safety.ParkingStateStore

/**
 * V0.6 experiment: rendering a single chosen app onto the car display, instead of mirroring the
 * whole phone screen.
 *
 * The mechanism that genuinely works: create an *own-content* VirtualDisplay (NOT
 * `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR`) and launch the app onto it via
 * `ActivityOptions.setLaunchDisplayId(displayId)`. That gives a per-app surface that is also
 * independent of the phone's screen power (see [ScreenOffController]).
 *
 * The honest constraints, documented rather than papered over:
 *  - `MirrorCoordinator` currently creates its single VirtualDisplay with AUTO_MIRROR. Per-app
 *    display requires the *other* kind of virtual display, so this cannot coexist with the
 *    zero-copy full-mirror on the same MediaProjection session (Android 14+ allows one display
 *    per projection). Switching modes therefore means tearing down and rebuilding the session.
 *  - Launching an arbitrary third-party activity onto a virtual display from a background service
 *    is restricted on modern Android and many apps set `FLAG_ACTIVITY_NEW_TASK`/resizeability
 *    constraints that cause them to bounce back to the default display. There is no guarantee an
 *    arbitrary app honors the target display.
 *
 * So this ships as a real, guarded launch path plus a pure eligibility check, with the
 * mode-switch/self-drawn work called out as the remaining step — consistent with how FILL/STRETCH
 * and screen-off are handled elsewhere.
 */
object PerAppDisplayController {
    private const val TAG = "AutoBridgePerApp"

    /**
     * Pure gate for whether a per-app launch onto [displayId] should be attempted. Fail-closed on
     * parked state (same rule as every other launch/input path) and requires a valid, non-default
     * display id (the phone's own display is [android.view.Display.DEFAULT_DISPLAY] == 0).
     */
    fun canLaunchOnDisplay(isParked: Boolean, displayId: Int): Boolean =
        isParked && displayId > 0

    /**
     * Attempts to launch [packageName] onto the virtual display [displayId]. Returns false (rather
     * than throwing) if not parked, the display is invalid, the package has no launch intent, or
     * the platform refuses the targeted-display launch.
     */
    fun launchOnDisplay(context: Context, packageName: String, displayId: Int): Boolean {
        if (!canLaunchOnDisplay(ParkingStateStore.isParked, displayId)) return false

        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return false

        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        return runCatching {
            context.startActivity(intent, options.toBundle())
            true
        }.onFailure { Log.w(TAG, "Per-app launch on display $displayId failed", it) }
            .getOrDefault(false)
    }
}
