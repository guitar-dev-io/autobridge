package dev.autobridge.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallerSourceTest {
    @Test
    fun playStoreInstallerIsTrusted() {
        assertTrue(InstallerSource.isTrusted("com.android.vending"))
    }

    @Test
    fun otherInstallersAreNotTrusted() {
        assertFalse(InstallerSource.isTrusted(null))
        assertFalse(InstallerSource.isTrusted("com.google.android.packageinstaller"))
        assertFalse(InstallerSource.isTrusted("com.android.shell"))
    }

    @Test
    fun installCommandSetsPlayStoreSourceInPlace() {
        assertEquals(
            listOf("pm", "install", "-i", "com.android.vending", "-r", "/data/app/base.apk"),
            InstallerSource.installWithPlayStoreSourceCommand("/data/app/base.apk")
        )
    }

    @Test
    fun readInstallerCommandUsesListPackages() {
        assertEquals(
            listOf("pm", "list", "packages", "-i", "dev.autobridge"),
            InstallerSource.readInstallerCommand("dev.autobridge")
        )
    }

    @Test
    fun parsesRecordedInstaller() {
        assertEquals(
            "com.android.vending",
            InstallerSource.parseInstaller("package:dev.autobridge  installer=com.android.vending")
        )
    }

    @Test
    fun treatsLiteralNullAsNoInstaller() {
        assertNull(InstallerSource.parseInstaller("package:dev.autobridge  installer=null"))
    }

    @Test
    fun missingInstallerTokenIsNull() {
        assertNull(InstallerSource.parseInstaller("package:dev.autobridge"))
        assertNull(InstallerSource.parseInstaller(""))
        assertNull(InstallerSource.parseInstaller(null))
    }

    @Test
    fun picksInstallerFromTheMatchingLine() {
        val output = """
            package:dev.autobridge  installer=com.android.vending
        """.trimIndent()
        assertEquals("com.android.vending", InstallerSource.parseInstaller(output))
    }
}
