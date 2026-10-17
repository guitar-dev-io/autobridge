package dev.autobridge.duoscreen.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuoScreenProtectedAppsTest {
    @Test fun netflixIsProtected() = assertTrue(DuoScreenProtectedApps.isProtected("com.netflix.mediaclient"))

    @Test fun mapsAndAnEmptyPaneAreNot() {
        assertFalse(DuoScreenProtectedApps.isProtected("com.google.android.apps.maps"))
        assertFalse(DuoScreenProtectedApps.isProtected(null))
    }
}
