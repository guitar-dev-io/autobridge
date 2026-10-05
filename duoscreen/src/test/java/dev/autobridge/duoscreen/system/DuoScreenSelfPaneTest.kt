package dev.autobridge.duoscreen.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DuoScreenSelfPaneTest {
    private val own = "dev.autobridge"

    @Test fun ourOwnPackageIsRecognised() {
        assertTrue(DuoScreenSelfPane.isSelf(own, own))
    }

    @Test fun anotherAppIsNot() {
        assertFalse(DuoScreenSelfPane.isSelf("com.google.android.apps.maps", own))
    }

    /** A debug/flavor applicationId is the runtime package, and only that one is ours. */
    @Test fun aSuffixedBuildOfOursIsNotTheSamePackage() {
        assertFalse(DuoScreenSelfPane.isSelf(own, "$own.debug"))
        assertTrue(DuoScreenSelfPane.isSelf("$own.debug", "$own.debug"))
    }

    @Test fun ourOwnPaneOpensTheCarBrowserRatherThanTheLauncherActivity() {
        assertEquals(DuoScreenSelfPane.ACTIVITY, DuoScreenSelfPane.activityOrNull(own, own))
        assertFalse("must not be the phone home UI", DuoScreenSelfPane.ACTIVITY.endsWith("MainActivity"))
    }

    @Test fun anotherAppResolvesItsLauncherActivityAsBefore() {
        assertNull(DuoScreenSelfPane.activityOrNull("com.google.android.apps.maps", own))
    }
}
