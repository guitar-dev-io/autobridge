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
        projecting: Boolean = false
    ) = MirrorReadiness.Status(
        notificationsRequired, notificationsGranted, shizukuRunning, shizukuGranted,
        realTouchAvailable, accessibilityEnabled, projecting
    )

    @Test
    fun `the steps are always the same three, in order`() {
        val steps = MirrorReadiness.steps(status())
        assertEquals(
            listOf(
                MirrorReadiness.Step.NOTIFICATIONS,
                MirrorReadiness.Step.TOUCH,
                MirrorReadiness.Step.CAPTURE
            ),
            steps.map { it.step }
        )
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
