package dev.autobridge.mirror

/**
 * What still has to happen before mirroring can work, as an ordered list.
 *
 * Mirroring needs three unrelated things — a notification channel the foreground service can post
 * to, an input backend that can deliver touches back to the phone, and a screen-capture consent
 * that Android only ever grants for one session at a time. They used to be reached from three
 * different places (a settings list, an accessibility screen, a button on the control centre), so
 * the usual failure was not a refusal but simply never finding the second step.
 *
 * The model is pure so the order, the wording and "what is blocking right now" are asserted in a
 * JVM test rather than by walking the screen on a device.
 */
object MirrorReadiness {

    enum class Step { NOTIFICATIONS, TOUCH, CAPTURE }

    enum class State {
        /** Nothing to do. */
        DONE,

        /** The user has to act, and this step is what mirroring is waiting for. */
        BLOCKING,

        /** Works without it, but something will be worse. Never blocks the next step. */
        OPTIONAL
    }

    /** A step as the screen renders it. */
    data class Item(
        val step: Step,
        val title: String,
        val caption: String,
        val state: State,
        val actionLabel: String?
    )

    /**
     * Everything the steps depend on, collected once by the caller so this object never touches
     * Android.
     *
     * @param notificationsRequired false below API 33, where the permission does not exist.
     */
    data class Status(
        val notificationsRequired: Boolean,
        val notificationsGranted: Boolean,
        val shizukuRunning: Boolean,
        val shizukuGranted: Boolean,
        val realTouchAvailable: Boolean,
        val accessibilityEnabled: Boolean,
        val projecting: Boolean
    )

    fun steps(status: Status): List<Item> = listOf(
        notifications(status),
        touch(status),
        capture(status)
    )

    /** The step the user should act on next, or null when mirroring is ready to start. */
    fun nextAction(status: Status): Item? =
        steps(status).firstOrNull { it.state == State.BLOCKING }

    private fun notifications(status: Status): Item = when {
        !status.notificationsRequired -> Item(
            Step.NOTIFICATIONS, "Notifications", "Not required on this Android version",
            State.DONE, null
        )
        status.notificationsGranted -> Item(
            Step.NOTIFICATIONS, "Notifications", "The mirroring service can show its status",
            State.DONE, null
        )
        // Projection runs without it; the user just loses the one control that stops it from
        // outside the app, so this is a warning and not a gate.
        else -> Item(
            Step.NOTIFICATIONS, "Notifications",
            "Without this there is no notification to stop mirroring from",
            State.OPTIONAL, "Allow"
        )
    }

    private fun touch(status: Status): Item = when {
        status.realTouchAvailable -> Item(
            Step.TOUCH, "Touch control", "Shizuku is connected — full multi-touch",
            State.DONE, null
        )
        status.shizukuGranted -> Item(
            Step.TOUCH, "Touch control", "Shizuku is connected — single touch only",
            State.DONE, null
        )
        status.accessibilityEnabled -> Item(
            Step.TOUCH, "Touch control",
            "Accessibility is on — taps and swipes work, pinch is synthetic",
            State.DONE, if (status.shizukuRunning) "Use Shizuku instead" else null
        )
        status.shizukuRunning -> Item(
            Step.TOUCH, "Touch control", "Shizuku is running but has not been granted to AutoBridge",
            State.BLOCKING, "Grant in Shizuku"
        )
        // Mirroring still shows the screen; it is touch coming back that stops working, which is
        // most of the point, so this blocks.
        else -> Item(
            Step.TOUCH, "Touch control", "Turn on the AutoBridge accessibility service",
            State.BLOCKING, "Open accessibility settings"
        )
    }

    private fun capture(status: Status): Item = when {
        status.projecting -> Item(
            Step.CAPTURE, "Screen capture", "Mirroring is running", State.DONE, "Stop"
        )
        // Android grants capture for one session only: there is nothing to pre-approve, so this
        // step is the start button rather than a permission.
        else -> Item(
            Step.CAPTURE, "Screen capture",
            "Android asks for this each time mirroring starts",
            State.BLOCKING, "Start mirroring"
        )
    }
}
