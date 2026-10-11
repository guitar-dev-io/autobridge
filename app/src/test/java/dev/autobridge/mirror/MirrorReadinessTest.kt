package dev.autobridge.mirror

import dev.autobridge.mirror.MirrorReadiness.CaptureStop
import dev.autobridge.mirror.MirrorReadiness.Cause
import dev.autobridge.mirror.MirrorReadiness.Fix
import dev.autobridge.mirror.MirrorReadiness.State
import dev.autobridge.mirror.MirrorReadiness.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorReadinessTest {

    /** A phone with nothing set up yet, Android Auto installed from the Play Store. */
    private fun status(
        notificationsRequired: Boolean = true,
        notificationsGranted: Boolean = true,
        shizukuRunning: Boolean = false,
        shizukuGranted: Boolean = false,
        realTouchAvailable: Boolean = false,
        accessibilityRunning: Boolean = false,
        projecting: Boolean = false,
        batteryOptimizationExempt: Boolean = true
    ) = MirrorReadiness.Status(
        notificationsRequired, notificationsGranted, shizukuRunning, shizukuGranted,
        realTouchAvailable, accessibilityRunning, projecting, batteryOptimizationExempt
    )

    /** Touch and capture done: nothing blocks. */
    private val ready = status(accessibilityRunning = true, projecting = true)

    private fun item(status: MirrorReadiness.Status, step: Step) =
        MirrorReadiness.steps(status).single { it.step == step }

    // ---------------------------------------------------------------- order and next

    @Test fun `the steps are always the same six, in order`() {
        assertEquals(
            listOf(Step.ANDROID_AUTO, Step.NOTIFICATIONS, Step.TOUCH, Step.CAPTURE, Step.BATTERY, Step.SPEED),
            MirrorReadiness.steps(status()).map { it.step }
        )
    }

    @Test fun `a missing input backend is what blocks first`() {
        assertEquals(Step.TOUCH, MirrorReadiness.nextAction(status(notificationsGranted = false))?.step)
    }

    @Test fun `every blocking step has a fix, so the one-tap button always has something to do`() {
        val worst = status(notificationsGranted = false, batteryOptimizationExempt = false)
            .copy(androidAutoInstalled = false)
        MirrorReadiness.steps(worst).filter { it.state == State.BLOCKING }.forEach {
            assertTrue("${it.step} blocks without a fix", it.fix != null)
        }
    }

    @Test fun `the fix button takes blocking steps before optional ones`() {
        val s = status(notificationsGranted = false, batteryOptimizationExempt = false)
        assertEquals(Fix.OPEN_ACCESSIBILITY, MirrorReadiness.nextFix(s)?.fix)
    }

    @Test fun `once nothing blocks, the fix button moves on to improvements`() {
        val s = ready.copy(notificationsGranted = false, batteryOptimizationExempt = false)
        assertNull(MirrorReadiness.nextAction(s))
        assertEquals(Fix.ALLOW_NOTIFICATIONS, MirrorReadiness.nextFix(s)?.fix)
    }

    @Test fun `nothing left to repair means no fix button`() {
        assertNull(MirrorReadiness.nextFix(ready))
    }

    // ---------------------------------------------------------------- Android Auto

    @Test fun `no Android Auto blocks, and the fix installs it`() {
        val aa = item(status().copy(androidAutoInstalled = false), Step.ANDROID_AUTO)
        assertEquals(State.BLOCKING, aa.state)
        assertEquals(Cause.AA_MISSING, aa.cause)
        assertEquals(Fix.INSTALL_ANDROID_AUTO, aa.fix)
    }

    @Test fun `a sideloaded install is a warning, fixable only where the build can reinstall itself`() {
        val sideloaded = status().copy(installerTrusted = false)
        assertEquals(Cause.AA_HIDDEN, item(sideloaded, Step.ANDROID_AUTO).cause)
        assertNull(item(sideloaded, Step.ANDROID_AUTO).fix)
        val fixable = item(sideloaded.copy(installerRepairAvailable = true), Step.ANDROID_AUTO)
        assertEquals(State.OPTIONAL, fixable.state)
        assertEquals(Fix.MAKE_VISIBLE_ON_CAR, fixable.fix)
    }

    @Test fun `a car session that has run settles the installer question`() {
        val aa = item(status().copy(installerTrusted = false, installerRepairAvailable = true, carSessionSeen = true), Step.ANDROID_AUTO)
        assertEquals(State.DONE, aa.state)
        assertNull(aa.fix)
    }

    @Test fun `connected reads as connected`() {
        val aa = item(status().copy(carConnected = true, carSessionSeen = true), Step.ANDROID_AUTO)
        assertEquals(Cause.AA_CONNECTED, aa.cause)
    }

    // ---------------------------------------------------------------- notifications

    @Test fun `notifications never block, because projection runs without them`() {
        val n = item(status(notificationsGranted = false), Step.NOTIFICATIONS)
        assertEquals(State.OPTIONAL, n.state)
        assertEquals(Fix.ALLOW_NOTIFICATIONS, n.fix)
    }

    @Test fun `below API 33 the notification step is simply done`() {
        val n = item(status(notificationsRequired = false, notificationsGranted = false), Step.NOTIFICATIONS)
        assertEquals(State.DONE, n.state)
        assertEquals(Cause.NOTIF_NOT_NEEDED, n.cause)
    }

    // ---------------------------------------------------------------- touch

    @Test fun `an unpermitted Shizuku sends the user to Shizuku, not to accessibility`() {
        val t = item(status(shizukuRunning = true), Step.TOUCH)
        assertEquals(Cause.TOUCH_SHIZUKU_NOT_GRANTED, t.cause)
        assertEquals(Fix.GRANT_SHIZUKU, t.fix)
    }

    @Test fun `accessibility alone satisfies touch, and still offers Shizuku when it is running`() {
        assertEquals(State.DONE, item(status(accessibilityRunning = true), Step.TOUCH).state)
        assertNull(item(status(accessibilityRunning = true), Step.TOUCH).fix)
        assertEquals(Fix.GRANT_SHIZUKU, item(status(accessibilityRunning = true, shizukuRunning = true), Step.TOUCH).fix)
    }

    @Test fun `a switched-on service Android stopped is named as such, not as off`() {
        val t = item(status().copy(accessibilityEnabledInSettings = true), Step.TOUCH)
        assertEquals(Cause.TOUCH_ACCESSIBILITY_STOPPED, t.cause)
        assertEquals(Fix.RESTART_ACCESSIBILITY, t.fix)
    }

    @Test fun `a restricted switch sends the user to App info`() {
        val t = item(status().copy(accessibilityRestricted = true), Step.TOUCH)
        assertEquals(Cause.TOUCH_ACCESSIBILITY_RESTRICTED, t.cause)
        assertEquals(Fix.ALLOW_RESTRICTED_SETTINGS, t.fix)
    }

    @Test fun `with nothing set up, accessibility is the offer, naming Shizuku when it is installed`() {
        assertEquals(Cause.TOUCH_NONE, item(status(), Step.TOUCH).cause)
        assertEquals(Cause.TOUCH_NONE_SHIZUKU_STOPPED, item(status().copy(shizukuInstalled = true), Step.TOUCH).cause)
        assertEquals(Fix.OPEN_ACCESSIBILITY, item(status().copy(shizukuInstalled = true), Step.TOUCH).fix)
    }

    // ---------------------------------------------------------------- capture

    @Test fun `capture blocks until started and turns into stop while running`() {
        assertEquals(Fix.START_CAPTURE, item(status(), Step.CAPTURE).fix)
        val running = item(status(projecting = true), Step.CAPTURE)
        assertEquals(State.DONE, running.state)
        assertEquals(Fix.STOP_CAPTURE, running.fix)
    }

    @Test fun `capture says how the last session ended`() {
        mapOf(
            CaptureStop.NONE to Cause.CAPTURE_NEEDED,
            CaptureStop.DENIED to Cause.CAPTURE_DENIED,
            CaptureStop.SYSTEM to Cause.CAPTURE_STOPPED_BY_SYSTEM,
            CaptureStop.FAILED to Cause.CAPTURE_FAILED
        ).forEach { (stop, cause) ->
            assertEquals(cause, item(status().copy(lastCaptureStop = stop), Step.CAPTURE).cause)
        }
    }

    @Test fun `timeline reasons map to how capture ended, and expected stops are not faults`() {
        assertEquals(CaptureStop.SYSTEM, MirrorReadiness.captureStopFromReason("reason=system"))
        assertEquals(CaptureStop.FAILED, MirrorReadiness.captureStopFromReason("reason=start_failed"))
        assertEquals(CaptureStop.FAILED, MirrorReadiness.captureStopFromReason("reason=display_failed"))
        assertEquals(CaptureStop.FAILED, MirrorReadiness.captureStopFromReason("reason=bind_failed"))
        listOf("reason=app", "reason=notification", "reason=host_disconnect", "reason=restarted", null).forEach {
            assertEquals(CaptureStop.NONE, MirrorReadiness.captureStopFromReason(it))
        }
    }

    // ---------------------------------------------------------------- battery

    @Test fun `background use never blocks, and asks only when not already exempt`() {
        val exempt = item(status(), Step.BATTERY)
        assertEquals(State.DONE, exempt.state)
        assertNull(exempt.fix)

        val notExempt = item(status(batteryOptimizationExempt = false), Step.BATTERY)
        assertEquals(State.OPTIONAL, notExempt.state)
        assertEquals(Fix.ALLOW_BATTERY, notExempt.fix)
        assertNull(MirrorReadiness.nextAction(ready.copy(batteryOptimizationExempt = false)))
    }

    @Test fun `with Shizuku granted the battery fix needs no system dialog`() {
        val b = item(status(batteryOptimizationExempt = false).copy(privilegedRepair = true), Step.BATTERY)
        assertEquals(Fix.EXEMPT_BATTERY_WITH_SHIZUKU, b.fix)
    }

    @Test fun `an OEM autostart list is pointed at without being called a fault`() {
        val b = item(status().copy(oemAutostartManager = true), Step.BATTERY)
        assertEquals(State.DONE, b.state)
        assertEquals(Cause.BATTERY_OK_CHECK_AUTOSTART, b.cause)
        assertEquals(Fix.OPEN_AUTOSTART, b.fix)
        // DONE, so it never becomes what the fix button runs.
        assertNull(MirrorReadiness.nextFix(ready.copy(oemAutostartManager = true)))
    }

    @Test fun `only the makers with their own autostart list are flagged`() {
        listOf("Xiaomi", "Redmi", "POCO").forEach { assertTrue(it, MirrorReadiness.hasOemAutostart(it)) }
        listOf("Google", "samsung", "OnePlus").forEach { assertFalse(it, MirrorReadiness.hasOemAutostart(it)) }
    }

    // ---------------------------------------------------------------- car speed

    @Test fun `car speed waits for a car and is never something to do before one connects`() {
        val s = item(status(), Step.SPEED)
        assertEquals(State.WAITING, s.state)
        assertNull(s.fix)
    }

    @Test fun `car speed is done once the car reports one`() {
        val s = item(status().copy(carConnected = true, speedPermissionGranted = true, speedReported = true), Step.SPEED)
        assertEquals(State.DONE, s.state)
    }

    @Test fun `car speed separates a missing permission from a car that sends nothing`() {
        assertEquals(Cause.SPEED_NO_PERMISSION, item(status().copy(carConnected = true), Step.SPEED).cause)
        assertEquals(
            Cause.SPEED_SILENT,
            item(status().copy(carConnected = true, speedPermissionGranted = true), Step.SPEED).cause
        )
    }

    @Test fun `car speed never becomes the next action or the next fix`() {
        listOf(
            ready,
            ready.copy(carConnected = true),
            ready.copy(carConnected = true, speedPermissionGranted = true),
            ready.copy(carConnected = true, speedPermissionGranted = true, speedReported = true)
        ).forEach {
            assertNull(MirrorReadiness.nextAction(it))
            assertNull(MirrorReadiness.nextFix(it))
        }
    }
}
