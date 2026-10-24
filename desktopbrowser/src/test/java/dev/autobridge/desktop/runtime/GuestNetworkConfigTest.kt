package dev.autobridge.desktop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.nio.file.Files

class GuestNetworkConfigTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun ips(vararg s: String) = s.map { InetAddress.getByName(it) }
    private fun ns(vararg s: String, v6: Boolean = false) = GuestNetworkConfig.nameservers(ips(*s), v6)

    @Test fun emptyInputGivesFallbacks() = assertEquals(listOf("8.8.8.8", "1.1.1.1"), ns())

    @Test fun loopbackOnlyGivesFallbacks() = assertEquals(listOf("8.8.8.8", "1.1.1.1"), ns("127.0.0.1", "::1", v6 = true))

    @Test fun networkServerFirstThenFallbacks() =
        assertEquals(listOf("192.168.20.10", "8.8.8.8", "1.1.1.1"), ns("192.168.20.10"))

    @Test fun twoNetworkServersKeepOnlyTheFirst() =
        assertEquals(listOf("10.0.0.1", "8.8.8.8", "1.1.1.1"), ns("10.0.0.1", "10.0.0.2"))

    @Test fun aFallbackTheNetworkAlreadyListsLeavesRoomForAnother() =
        assertEquals(listOf("8.8.8.8", "8.8.4.4", "1.1.1.1"), ns("8.8.8.8", "8.8.4.4"))

    @Test fun duplicatesCollapse() {
        assertEquals(listOf("192.168.1.1", "8.8.8.8", "1.1.1.1"), ns("192.168.1.1", "192.168.1.1"))
        // A fallback the network lists itself is not repeated and keeps the network's order.
        assertEquals(listOf("192.168.1.1", "1.1.1.1", "8.8.8.8"), ns("192.168.1.1", "192.168.1.1", "1.1.1.1"))
    }

    @Test fun linkLocalAnyAndMulticastAreDropped() =
        assertEquals(listOf("8.8.8.8", "1.1.1.1"), ns("169.254.1.1", "fe80::1", "0.0.0.0", "::", "224.0.0.1", v6 = true))

    @Test fun ipv6OnlyWhenRoutable() {
        assertEquals(listOf("2001:db8:0:0:0:0:0:53", "8.8.8.8", "1.1.1.1"), ns("2001:db8::53", v6 = true))
        assertEquals(listOf("8.8.8.8", "1.1.1.1"), ns("2001:db8::53", v6 = false))
    }

    @Test fun ipv4BeforeIpv6() =
        assertEquals(listOf("192.168.1.1", "8.8.8.8", "1.1.1.1"), ns("2001:db8::53", "192.168.1.1", v6 = true))

    @Test fun neverMoreThanThreeNameservers() {
        val text = GuestNetworkConfig.resolvConf(ips("10.0.0.1", "10.0.0.2", "10.0.0.3", "10.0.0.4", "10.0.0.5", "10.0.0.6"), false)
        assertEquals(GuestNetworkConfig.MAX_NAMESERVERS, text.lines().count { it.startsWith("nameserver ") })
    }

    @Test fun fullText() = assertEquals(
        GuestNetworkConfig.HEADER + "\nnameserver 192.168.20.10\nnameserver 8.8.8.8\nnameserver 1.1.1.1\noptions timeout:2 attempts:2\n",
        GuestNetworkConfig.resolvConf(ips("192.168.20.10"), false),
    )

    private fun rootfs(): File = tmp.newFolder("rootfs").also { File(it, "etc").mkdirs() }

    @Test fun writeOnlyWhenChanged() {
        val root = rootfs()
        val target = File(root, "etc/resolv.conf")
        assertTrue(GuestNetworkConfig.write(root, "a\n"))
        assertEquals("a\n", target.readText())
        target.setLastModified(1_000_000L)
        assertFalse(GuestNetworkConfig.write(root, "a\n"))
        assertEquals(1_000_000L, target.lastModified())
        assertTrue(GuestNetworkConfig.write(root, "b\n"))
        assertEquals("b\n", target.readText())
        assertFalse(File(root, "etc/.resolv.conf.autobridge.tmp").exists())
    }

    @Test fun aSymlinkIsReplacedNotFollowed() {
        val root = rootfs()
        val outside = tmp.newFile("host-file").apply { writeText("host\n") }
        val link = File(root, "etc/resolv.conf").toPath()
        Files.createSymbolicLink(link, outside.toPath())
        assertTrue(GuestNetworkConfig.write(root, "x\n"))
        assertFalse(Files.isSymbolicLink(link))
        assertEquals("x\n", link.toFile().readText())
        assertEquals("host\n", outside.readText())
    }

    @Test fun missingEtcWritesNothing() {
        val root = tmp.newFolder("bare")
        assertFalse(GuestNetworkConfig.write(root, "x\n"))
        assertFalse(File(root, "etc").exists())
    }
}
