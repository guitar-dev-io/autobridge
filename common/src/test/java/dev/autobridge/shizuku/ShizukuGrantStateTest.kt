package dev.autobridge.shizuku

import dev.autobridge.shizuku.ShizukuGrant.State
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the user is told to fix, in the order they have to fix it. */
class ShizukuGrantStateTest {
    @Test fun nothingInstalledAsksForTheInstall() =
        assertEquals(State.NOT_INSTALLED, ShizukuGrant.stateOf(installed = false, running = false, granted = false))

    @Test fun installedButStoppedAsksToStartIt() =
        assertEquals(State.NOT_RUNNING, ShizukuGrant.stateOf(installed = true, running = false, granted = false))

    @Test fun runningWithoutTheGrantAsksToAllow() =
        assertEquals(State.NOT_GRANTED, ShizukuGrant.stateOf(installed = true, running = true, granted = false))

    @Test fun grantedIsReady() =
        assertEquals(State.READY, ShizukuGrant.stateOf(installed = true, running = true, granted = true))

    /** A fork under another package still serves the binder; it is not "not installed". */
    @Test fun aRunningForkCountsAsInstalled() =
        assertEquals(State.NOT_GRANTED, ShizukuGrant.stateOf(installed = false, running = true, granted = false))
}
