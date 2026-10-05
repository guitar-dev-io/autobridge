package dev.autobridge.duoscreen.system

import android.content.ComponentName
import android.view.Surface
import android.view.MotionEvent

/**
 * The things Duo Screen cannot do with its own app privileges.
 *
 * The launch and the input injection need the shell UID: a launch that arrives with shell
 * privileges lands on an untrusted display without bouncing back to the default one, and shell
 * touch injection reaches it (both verified on-device 2026-10-04).
 *
 * Creating the pane display can be done by an ordinary app as a private, own-content
 * VirtualDisplay with no permission at all (see [dev.autobridge.duoscreen.render.DuoScreenDisplays]),
 * and that stays the fallback. But such a display lives in the *default display group*, so when
 * the hardware POWER button puts the device into real sleep the system stops composing it and the
 * car/DHU pane goes black. [createTrustedVirtualDisplay] offers a privileged alternative — a
 * trusted display in its own display group, which the power button cannot take down — because the
 * shell UID holds `ADD_TRUSTED_DISPLAY`, which an app process is refused.
 */
interface DuoScreenPrivilegedOps {
    val isAvailable: Boolean

    /**
     * Creates a *trusted*, own-display-group VirtualDisplay as the shell UID and returns its
     * display id, or -1 when the privilege is unavailable or the creation fails on this ROM. A
     * trusted display in its own group does not follow display 0's power state, so the car pane
     * keeps composing after the phone's hardware power button blanks the phone panel — the exact
     * failure a plain app-owned VirtualDisplay cannot survive.
     *
     * Returns -1 rather than throwing so the caller can fall back to the ordinary untrusted
     * [android.hardware.display.DisplayManager.createVirtualDisplay] path and behave exactly as
     * before. [flags] carries the trusted flag set the caller chose (see
     * [dev.autobridge.duoscreen.render.DuoScreenDisplays]).
     */
    fun createTrustedVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        flags: Int,
    ): Int = -1

    /**
     * Resizes a trusted display previously returned by [createTrustedVirtualDisplay], matched by
     * its [displayId]. Returns false when the privilege is unavailable or the call fails. No-op
     * (false) by default.
     */
    fun resizeTrustedVirtualDisplay(displayId: Int, width: Int, height: Int, dpi: Int): Boolean = false

    /**
     * Points a trusted display previously returned by [createTrustedVirtualDisplay] at [surface]
     * (null detaches it). Returns false when the privilege is unavailable or the call fails. No-op
     * (false) by default.
     */
    fun setTrustedVirtualDisplaySurface(displayId: Int, surface: Surface?): Boolean = false

    /**
     * Releases a trusted display previously returned by [createTrustedVirtualDisplay]. A shell-UID
     * display is registered to the system by the shell transaction, so it is torn down through the
     * same seam rather than by an app-side `VirtualDisplay.release()`. No-op by default.
     */
    fun releaseTrustedVirtualDisplay(displayId: Int) {}

    /**
     * Launches [packageName]'s launcher activity onto [displayId]. When [component] is non-null it
     * is launched explicitly (the caller pre-resolved it); when null the implicit
     * MAIN/LAUNCHER + setPackage intent is used as a fallback.
     *
     * [allowSecondInstance] adds FLAG_ACTIVITY_MULTIPLE_TASK, so an app that already has a task on
     * another display gets a *new* one here instead of having its existing task moved onto this
     * display. Off by default: for a third-party app, reusing the task it already has is normally
     * what the driver means. [DuoScreenSelfPane] explains why our own app always needs it on.
     */
    fun launchOnDisplay(
        displayId: Int,
        packageName: String,
        component: ComponentName? = null,
        allowSecondInstance: Boolean = false,
    ): Boolean

    /** Injects [event] into [displayId]; the event is recycled by the caller, not here. */
    fun injectMotion(event: MotionEvent, displayId: Int): Boolean
}
