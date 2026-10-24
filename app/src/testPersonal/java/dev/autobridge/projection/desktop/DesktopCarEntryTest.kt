package dev.autobridge.projection.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopCarEntryTest {
    @Test
    fun detectsOnlyTheCarEntryAlias() {
        assertTrue(DesktopCarEntry.isCarEntry("dev.autobridge.projection.desktop.DesktopBrowserCarEntry"))
        assertFalse(DesktopCarEntry.isCarEntry("dev.autobridge.projection.desktop.DesktopBrowserActivity"))
        assertFalse(DesktopCarEntry.isCarEntry("dev.autobridge.projection.desktop.DesktopBrowserPane"))
        assertFalse(DesktopCarEntry.isCarEntry(null))
    }

    @Test
    fun gateNeedsParkedAndPolicy() {
        assertTrue(DesktopCarEntry.allowed(gateParked = true, browserAvailable = true))
        assertFalse(DesktopCarEntry.allowed(gateParked = false, browserAvailable = true))
        assertFalse(DesktopCarEntry.allowed(gateParked = true, browserAvailable = false))
        assertFalse(DesktopCarEntry.allowed(gateParked = false, browserAvailable = false))
    }

    @Test
    fun aliasNameMatchesManifest() {
        assertEquals("DesktopBrowserCarEntry", DesktopCarEntry.NAME.substringAfterLast('.'))
    }
}
