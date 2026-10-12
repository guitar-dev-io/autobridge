package dev.autobridge.mirror

/**
 * What still has to happen before the car works, as an ordered list, with the reason each step is
 * not done and the one action that fixes it.
 *
 * Using the car needs several unrelated things — Android Auto itself, a notification channel the
 * foreground service can post to, an input backend that can deliver touches back to the phone, a
 * screen-capture consent that Android only ever grants for one session at a time, and an OS that
 * does not kill the app in the background. They live in different system screens, and the usual
 * failure was not a refusal but a step nobody could find, or one that looked on and was not (an
 * accessibility service Android had quietly stopped). So each step reports a [Cause] — what is
 * wrong, specifically — and a [Fix] — the single tap that repairs it — instead of "try again".
 *
 * The model is pure: it holds no wording, only causes and fixes, so the order, "what is blocking
 * right now" and "which fix comes next" are asserted in a JVM test, and the screen resolves each
 * cause to translated text.
 */
object MirrorReadiness {

    enum class Step { ANDROID_AUTO, NOTIFICATIONS, TOUCH, CAPTURE, BATTERY, SPEED }

    enum class State {
        /** Nothing to do. */
        DONE,

        /** The user has to act, and this step is what the car is waiting for. */
        BLOCKING,

        /** Works without it, but something will be worse. Never blocks the next step. */
        OPTIONAL,

        /** Cannot be checked yet (the car is not connected). Nothing to do, nothing wrong. */
        WAITING
    }

    /** Why a step is in its state; the screen shows one sentence per cause. */
    enum class Cause {
        AA_MISSING, AA_HIDDEN_FIXABLE, AA_HIDDEN, AA_CONNECTED, AA_READY,
        NOTIF_NOT_NEEDED, NOTIF_OK, NOTIF_MISSING,
        TOUCH_SHIZUKU_FULL, TOUCH_SHIZUKU, TOUCH_ACCESSIBILITY,
        TOUCH_SHIZUKU_NOT_GRANTED, TOUCH_ACCESSIBILITY_STOPPED, TOUCH_ACCESSIBILITY_RESTRICTED,
        TOUCH_NONE_SHIZUKU_STOPPED, TOUCH_NONE,
        CAPTURE_RUNNING, CAPTURE_DENIED, CAPTURE_STOPPED_BY_SYSTEM, CAPTURE_FAILED, CAPTURE_NEEDED,
        BATTERY_OK, BATTERY_OK_CHECK_AUTOSTART, BATTERY_RESTRICTED,
        SPEED_WAITING, SPEED_OK, SPEED_NO_PERMISSION, SPEED_SILENT
    }

    /** The one action a step offers. Each opens the exact screen, or does the repair itself. */
    enum class Fix {
        INSTALL_ANDROID_AUTO,
        /** Reinstalls in place with the Play Store as installer (root or Shizuku), so Android Auto lists the app. */
        MAKE_VISIBLE_ON_CAR,
        ALLOW_NOTIFICATIONS,
        GRANT_SHIZUKU,
        OPEN_ACCESSIBILITY,
        /** Same screen as [OPEN_ACCESSIBILITY]; the service is on but stopped, so it is switched off and on. */
        RESTART_ACCESSIBILITY,
        /** App info, where Android 13+ hides "Allow restricted settings" for a sideloaded app. */
        ALLOW_RESTRICTED_SETTINGS,
        START_CAPTURE,
        STOP_CAPTURE,
        /** Adds the app to the battery allowlist through Shizuku, with no system dialog. */
        EXEMPT_BATTERY_WITH_SHIZUKU,
        ALLOW_BATTERY,
        /** The OEM's own autostart list (MIUI/HyperOS and similar), which no API can read. */
        OPEN_AUTOSTART
    }

    /** How the last capture session ended, as far as the app saw it. */
    enum class CaptureStop { NONE, DENIED, SYSTEM, FAILED }

    /** A step as the screen renders it. */
    data class Item(val step: Step, val state: State, val cause: Cause, val fix: Fix?)

    /**
     * Everything the steps depend on, collected once by the caller so this object never touches
     * Android.
     *
     * @param notificationsRequired false below API 33, where the permission does not exist.
     * @param accessibilityRunning the AutoBridge accessibility service is connected right now.
     * @param batteryOptimizationExempt whether the OS has already agreed not to restrict this app
     *        in the background — see [android.os.PowerManager.isIgnoringBatteryOptimizations].
     * @param carConnected whether an Android Auto host is connected right now.
     * @param speedPermissionGranted whether the car speed permission (`CAR_SPEED`) is granted.
     * @param speedReported whether the car has reported a speed this connection.
     * @param installerTrusted the Play Store is the recorded installer, so Android Auto lists the app.
     * @param carSessionSeen an AutoBridge car session has started on this phone, which proves the
     *        car lists the app whatever the installer says (e.g. Android Auto's unknown sources).
     * @param installerRepairAvailable this build can reinstall itself with a trusted installer.
     * @param accessibilityEnabledInSettings the service is switched on in Android's settings.
     * @param accessibilityRestricted Android 13+ greys the switch out for this sideloaded app.
     * @param privilegedRepair Shizuku is granted and allowed in this mode, so a repair can run
     *        without a system screen.
     * @param oemAutostartManager the phone's maker runs its own autostart list on top of Android's.
     */
    data class Status(
        val notificationsRequired: Boolean,
        val notificationsGranted: Boolean,
        val shizukuRunning: Boolean,
        val shizukuGranted: Boolean,
        val realTouchAvailable: Boolean,
        val accessibilityRunning: Boolean,
        val projecting: Boolean,
        val batteryOptimizationExempt: Boolean = true,
        val carConnected: Boolean = false,
        val speedPermissionGranted: Boolean = false,
        val speedReported: Boolean = false,
        val androidAutoInstalled: Boolean = true,
        val installerTrusted: Boolean = true,
        val carSessionSeen: Boolean = false,
        val installerRepairAvailable: Boolean = false,
        val shizukuInstalled: Boolean = false,
        val accessibilityEnabledInSettings: Boolean = false,
        val accessibilityRestricted: Boolean = false,
        val lastCaptureStop: CaptureStop = CaptureStop.NONE,
        val privilegedRepair: Boolean = false,
        val oemAutostartManager: Boolean = false
    )

    fun steps(status: Status): List<Item> = listOf(
        androidAuto(status),
        notifications(status),
        touch(status),
        capture(status),
        battery(status),
        speed(status)
    )

    /** The step the user should act on next, or null when nothing blocks the car. */
    fun nextAction(status: Status): Item? =
        steps(status).firstOrNull { it.state == State.BLOCKING }

    /**
     * What the one-tap "Fix" button does: the first blocking step, or, once nothing blocks, the
     * first optional improvement that has a fix. Null when there is nothing left to repair.
     */
    fun nextFix(status: Status): Item? {
        val steps = steps(status)
        return steps.firstOrNull { it.state == State.BLOCKING && it.fix != null }
            ?: steps.firstOrNull { it.state == State.OPTIONAL && it.fix != null }
    }

    /**
     * How the last capture ended, from the reason [dev.autobridge.car.ConnectionTimeline] recorded
     * for it (`reason=…`). Stops the driver asked for, or that were expected, are not a fault.
     */
    fun captureStopFromReason(detail: String?): CaptureStop = when (detail?.removePrefix("reason=")) {
        "system" -> CaptureStop.SYSTEM
        "start_failed", "display_failed", "bind_failed" -> CaptureStop.FAILED
        else -> CaptureStop.NONE
    }

    /** Makers whose own autostart list can stop an app that Android itself allows (MIUI/HyperOS). */
    fun hasOemAutostart(manufacturer: String): Boolean =
        manufacturer.lowercase() in setOf("xiaomi", "redmi", "poco")

    private fun androidAuto(status: Status): Item = when {
        !status.androidAutoInstalled ->
            Item(Step.ANDROID_AUTO, State.BLOCKING, Cause.AA_MISSING, Fix.INSTALL_ANDROID_AUTO)
        status.carConnected && status.carSessionSeen ->
            Item(Step.ANDROID_AUTO, State.DONE, Cause.AA_CONNECTED, null)
        status.installerTrusted || status.carSessionSeen ->
            Item(Step.ANDROID_AUTO, State.DONE, Cause.AA_READY, null)
        // Not a gate: Android Auto's developer "Unknown sources" also lists the app, and that
        // cannot be read from here. It turns DONE the first time a car session starts.
        status.installerRepairAvailable ->
            Item(Step.ANDROID_AUTO, State.OPTIONAL, Cause.AA_HIDDEN_FIXABLE, Fix.MAKE_VISIBLE_ON_CAR)
        else -> Item(Step.ANDROID_AUTO, State.OPTIONAL, Cause.AA_HIDDEN, null)
    }

    private fun notifications(status: Status): Item = when {
        !status.notificationsRequired ->
            Item(Step.NOTIFICATIONS, State.DONE, Cause.NOTIF_NOT_NEEDED, null)
        status.notificationsGranted ->
            Item(Step.NOTIFICATIONS, State.DONE, Cause.NOTIF_OK, null)
        // Projection runs without it; the user just loses the one control that stops it from
        // outside the app, so this is a warning and not a gate.
        else -> Item(Step.NOTIFICATIONS, State.OPTIONAL, Cause.NOTIF_MISSING, Fix.ALLOW_NOTIFICATIONS)
    }

    private fun touch(status: Status): Item = when {
        status.realTouchAvailable ->
            Item(Step.TOUCH, State.DONE, Cause.TOUCH_SHIZUKU_FULL, null)
        status.shizukuGranted ->
            Item(Step.TOUCH, State.DONE, Cause.TOUCH_SHIZUKU, null)
        status.accessibilityRunning -> Item(
            Step.TOUCH, State.DONE, Cause.TOUCH_ACCESSIBILITY,
            if (status.shizukuRunning) Fix.GRANT_SHIZUKU else null
        )
        status.shizukuRunning ->
            Item(Step.TOUCH, State.BLOCKING, Cause.TOUCH_SHIZUKU_NOT_GRANTED, Fix.GRANT_SHIZUKU)
        // Switched on, not running: Android (or an OEM battery manager) stopped it. Turning it off
        // and on again is the repair, and saying so beats "turn on accessibility" for a switch
        // that already reads on.
        status.accessibilityEnabledInSettings ->
            Item(Step.TOUCH, State.BLOCKING, Cause.TOUCH_ACCESSIBILITY_STOPPED, Fix.RESTART_ACCESSIBILITY)
        status.accessibilityRestricted ->
            Item(Step.TOUCH, State.BLOCKING, Cause.TOUCH_ACCESSIBILITY_RESTRICTED, Fix.ALLOW_RESTRICTED_SETTINGS)
        // Mirroring still shows the screen; it is touch coming back that stops working, which is
        // most of the point, so this blocks.
        status.shizukuInstalled ->
            Item(Step.TOUCH, State.BLOCKING, Cause.TOUCH_NONE_SHIZUKU_STOPPED, Fix.OPEN_ACCESSIBILITY)
        else -> Item(Step.TOUCH, State.BLOCKING, Cause.TOUCH_NONE, Fix.OPEN_ACCESSIBILITY)
    }

    private fun capture(status: Status): Item = when {
        status.projecting -> Item(Step.CAPTURE, State.DONE, Cause.CAPTURE_RUNNING, Fix.STOP_CAPTURE)
        // Android grants capture for one session only: there is nothing to pre-approve, so this
        // step is the start button, and what went wrong last time is the useful part.
        else -> Item(
            Step.CAPTURE, State.BLOCKING,
            when (status.lastCaptureStop) {
                CaptureStop.DENIED -> Cause.CAPTURE_DENIED
                CaptureStop.SYSTEM -> Cause.CAPTURE_STOPPED_BY_SYSTEM
                CaptureStop.FAILED -> Cause.CAPTURE_FAILED
                CaptureStop.NONE -> Cause.CAPTURE_NEEDED
            },
            Fix.START_CAPTURE
        )
    }

    private fun battery(status: Status): Item = when {
        // Exempt from Android's optimisation, but MIUI/HyperOS and similar keep their own
        // autostart list that no API reports; point at it, without claiming it is wrong.
        status.batteryOptimizationExempt && status.oemAutostartManager ->
            Item(Step.BATTERY, State.DONE, Cause.BATTERY_OK_CHECK_AUTOSTART, Fix.OPEN_AUTOSTART)
        status.batteryOptimizationExempt ->
            Item(Step.BATTERY, State.DONE, Cause.BATTERY_OK, null)
        // A warning, not a gate: everything works right up until the OS decides to end the process.
        else -> Item(
            Step.BATTERY, State.OPTIONAL, Cause.BATTERY_RESTRICTED,
            if (status.privilegedRepair) Fix.EXEMPT_BATTERY_WITH_SHIZUKU else Fix.ALLOW_BATTERY
        )
    }

    /**
     * Whether the car reports its speed, which is what the parked status is read from. It never
     * blocks: the parked gate in this build only reports. The permission can only be granted from
     * the car screen, so there is no phone action.
     */
    private fun speed(status: Status): Item = when {
        !status.carConnected -> Item(Step.SPEED, State.WAITING, Cause.SPEED_WAITING, null)
        status.speedReported -> Item(Step.SPEED, State.DONE, Cause.SPEED_OK, null)
        !status.speedPermissionGranted -> Item(Step.SPEED, State.OPTIONAL, Cause.SPEED_NO_PERMISSION, null)
        else -> Item(Step.SPEED, State.OPTIONAL, Cause.SPEED_SILENT, null)
    }
}
