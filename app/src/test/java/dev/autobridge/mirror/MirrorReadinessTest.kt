package dev.autobridge.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MirrorReadinessTest {

    private fun status(
        notificationsRequired: Boolean = true,
        notificationsGranted: Boolean = true,
        shizukuRunning: Boolean = false,
        shizukuGranted: Boolean = false,
        realTouchAvailable: Boolean = false,
        accessibilityEnabled: Boolean = false,
        projecting: Boolean = false,
        batteryOptimizationExempt: Boolean = true
    ) = MirrorReadiness.Status(
        notificationsRequired, notificationsGranted, shizukuRunning, shizukuGranted,
        realTouchAvailable, accessibilityEnabled, projecting, batteryOptimizationExempt
    )

    @Test
    fun `the steps are always the same four, in order`() {
        val steps = MirrorReadiness.steps(status())
        assertEquals(
            listOf(
                MirrorReadiness.Step.NOTIFICATIONS,
                MirrorReadiness.Step.TOUCH,
                MirrorReadiness.Step.CAPTURE,
                MirrorReadiness.Step.BATTERY
            ),
            steps.map { it.step }
        )
    }

    @Test
    fun `background use never blocks, and asks only when not already exempt`() {
        val exempt = MirrorReadiness.steps(status()).last()
        assertEquals(MirrorReadiness.State.DONE, exempt.state)
        assertNull(exempt.actionLabel)

        val notExempt = MirrorReadiness.steps(status(batteryOptimizationExempt = false)).last()
        assertEquals(MirrorReadiness.State.OPTIONAL, notExempt.state)
        assertEquals("Allow", notExempt.actionLabel)
        // Everything else already satisfied: an OPTIONAL battery step must not become "next".
        val otherwiseReady = status(
            accessibilityEnabled = true, projecting = true, batteryOptimizationExempt = false
        )
        assertNull(MirrorReadiness.nextAction(otherwiseReady))
    }

    @Test
    fun `a missing input backend is what blocks first`() {
        val next = MirrorReadiness.nextAction(status(notificationsGranted = false))
        assertEquals(MirrorReadiness.Step.TOUCH, next?.step)
    }

    @Test
    fun `notifications never block, because projection runs without them`() {
        val notifications = MirrorReadiness.steps(status(notificationsGranted = false)).first()
        assertEquals(MirrorReadiness.State.OPTIONAL, notifications.state)
    }

    @Test
    fun `an unpermitted Shizuku sends the user to Shizuku, not to accessibility`() {
        val touch = MirrorReadiness.steps(status(shizukuRunning = true))[1]
        assertEquals(MirrorReadiness.State.BLOCKING, touch.state)
        assertEquals("Grant in Shizuku", touch.actionLabel)
    }

    @Test
    fun `accessibility alone satisfies touch, and still offers Shizuku when it is running`() {
        val onlyAccessibility = MirrorReadiness.steps(status(accessibilityEnabled = true))[1]
        assertEquals(MirrorReadiness.State.DONE, onlyAccessibility.state)
        assertNull(onlyAccessibility.actionLabel)

        val withShizuku = MirrorReadiness.steps(
            status(accessibilityEnabled = true, shizukuRunning = true)
        )[1]
        assertEquals(MirrorReadiness.State.DONE, withShizuku.state)
        assertEquals("Use Shizuku instead", withShizuku.actionLabel)
    }

    @Test
    fun `capture is the last thing asked for and turns into stop while running`() {
        val ready = status(accessibilityEnabled = true)
        assertEquals(MirrorReadiness.Step.CAPTURE, MirrorReadiness.nextAction(ready)?.step)
        assertEquals("Start mirroring", MirrorReadiness.nextAction(ready)?.actionLabel)

        val running = status(accessibilityEnabled = true, projecting = true)
        assertNull(MirrorReadiness.nextAction(running))
        assertEquals("Stop", MirrorReadiness.steps(running)[2].actionLabel)
    }

    @Test
    fun `below API 33 the notification step is simply done`() {
        val step = MirrorReadiness.steps(
            status(notificationsRequired = false, notificationsGranted = false)
        ).first()
        assertEquals(MirrorReadiness.State.DONE, step.state)
    }
}
