package dev.autobridge.duoscreen.system

import dev.autobridge.duoscreen.system.DuoScreenLauncherResolver.LauncherActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DuoScreenLauncherResolverTest {
    @Test fun singleMatchYieldsExplicitActivity() {
        val activity = DuoScreenLauncherResolver.explicitActivityOrNull(
            "dev.autobridge",
            listOf(LauncherActivity("dev.autobridge", "dev.autobridge.MainActivity"))
        )
        assertEquals("dev.autobridge", activity?.packageName)
        assertEquals("dev.autobridge.MainActivity", activity?.activityName)
    }

    @Test fun multipleMatchesKeepTheFirstBestMatch() {
        val activity = DuoScreenLauncherResolver.explicitActivityOrNull(
            "dev.autobridge",
            listOf(
                LauncherActivity("dev.autobridge", "dev.autobridge.MainActivity"),
                LauncherActivity("dev.autobridge", "dev.autobridge.AltActivity")
            )
        )
        assertEquals("dev.autobridge.MainActivity", activity?.activityName)
    }

    @Test fun candidatesForAnotherPackageYieldNull() {
        val activity = DuoScreenLauncherResolver.explicitActivityOrNull(
            "dev.autobridge",
            listOf(LauncherActivity("com.other.app", "com.other.app.Main"))
        )
        assertNull(activity)
    }

    @Test fun emptyCandidatesYieldNull() {
        assertNull(DuoScreenLauncherResolver.explicitActivityOrNull("dev.autobridge", emptyList()))
    }
}
