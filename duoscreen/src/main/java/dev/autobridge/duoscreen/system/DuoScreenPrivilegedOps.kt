package dev.autobridge.duoscreen.system

import android.content.ComponentName
import android.view.MotionEvent

/**
 * The only two things Duo Screen cannot do with its own app privileges.
 *
 * Creating the pane displays is deliberately *not* here: an ordinary app may create a private,
 * own-content VirtualDisplay with no permission at all (see [dev.autobridge.duoscreen.render.DuoScreenDisplays]), and a launch that
 * arrives with shell privileges lands on such an untrusted display without bouncing back to the
 * default one — both verified on-device 2026-10-04. Only the launch and the input injection need
 * the shell UID.
 */
interface DuoScreenPrivilegedOps {
    val isAvailable: Boolean

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
