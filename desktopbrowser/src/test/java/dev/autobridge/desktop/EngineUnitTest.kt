package dev.autobridge.desktop

import dev.autobridge.desktop.browser.BraveAdapter
import dev.autobridge.desktop.browser.ChromeAdapter
import dev.autobridge.desktop.browser.LaunchSpec
import dev.autobridge.desktop.display.DisplayMapping
import dev.autobridge.desktop.display.XwdFramebuffer
import dev.autobridge.desktop.input.Keysym
import dev.autobridge.desktop.install.AptStatus
import dev.autobridge.desktop.install.OpenPgpKeys
import dev.autobridge.desktop.install.RuntimeSource
import dev.autobridge.desktop.install.VerifiedDownloader
import dev.autobridge.desktop.runtime.ProotCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class EngineUnitTest {
    private fun resource(name: String): ByteArray = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    @Test fun googleKeyMatchesItsPinnedFingerprint() {
        val found = OpenPgpKeys.primaryFingerprints(resource("keys/google-linux-signing-key.pub"))
        assertEquals(listOf("EB4C1BFD4F042F6DDDCCEC917721F63BD38B4796"), found)
        assertTrue(found.all { it in ChromeAdapter.pinnedFingerprints })
    }

    @Test fun braveKeyringMatchesItsPinnedFingerprints() {
        val found = OpenPgpKeys.primaryFingerprints(resource("keys/brave-browser-archive-keyring.gpg"))
        assertEquals(BraveAdapter.pinnedFingerprints, found.toSet())
    }

    @Test fun aKeyFromTheOtherVendorIsNotAccepted() {
        val brave = OpenPgpKeys.primaryFingerprints(resource("keys/brave-browser-archive-keyring.gpg"))
        assertFalse(brave.all { it in ChromeAdapter.pinnedFingerprints })
    }

    @Test fun aptStatusLinesParse() {
        val dl = AptStatus.parse("dlstatus:google-chrome-stable:42.5:Retrieving file 3 of 9")!!
        assertEquals(AptStatus.Phase.DOWNLOAD, dl.phase)
        assertEquals(42.5f, dl.percent)
        assertEquals("Retrieving file 3 of 9", dl.message)
        val pm = AptStatus.parse("pmstatus:libgtk-3-0t64:61.1:Unpacking libgtk-3-0t64:arm64 (3.24.49-3)")!!
        assertEquals("Unpacking libgtk-3-0t64:arm64 (3.24.49-3)", pm.message)
        assertNull(AptStatus.parse("Get:1 http://deb.debian.org/debian trixie InRelease"))
    }

    @Test fun aptPolicyParses() {
        val out = """
            google-chrome-stable:
              Installed: 155.0.8059.84-1
              Candidate: 155.0.8059.96-1
              Version table:
        """.trimIndent()
        assertEquals("155.0.8059.84-1" to "155.0.8059.96-1", AptStatus.parsePolicy(out))
        assertEquals(null to "1.97.59", AptStatus.parsePolicy("brave-browser:\n  Installed: (none)\n  Candidate: 1.97.59\n"))
    }

    @Test fun prootCommandIsolatesTheGuestEnvironment() {
        val cmd = ProotCommand.build(
            prootPath = "/n/libproot.so", loaderPath = "/n/libproot-loader.so", prootTmpDir = "/c/tmp",
            rootfsPath = "/f/rootfs", command = listOf("apt-get", "update"),
            guestEnvironment = mapOf("DISPLAY" to ":1"), fakeProcFiles = mapOf("/proc/stat" to "/f/rootfs/.p/stat"),
        )
        val argv = cmd.argv
        assertEquals("/n/libproot.so", argv.first())
        assertTrue(argv.containsAll(listOf("--kill-on-exit", "--link2symlink", "--sysvipc", "-0")))
        assertEquals("/f/rootfs", argv[argv.indexOf("-r") + 1])
        assertTrue(argv.contains("/f/rootfs/tmp:/dev/shm"))
        assertTrue(argv.contains("/f/rootfs/.p/stat:/proc/stat"))
        val envIndex = argv.indexOf("-i")
        assertEquals("/usr/bin/env", argv[envIndex - 1])
        assertTrue(argv.contains("DISPLAY=:1"))
        assertTrue(argv.contains("DBUS_SESSION_BUS_ADDRESS=disabled:"))
        assertEquals(listOf("apt-get", "update"), argv.takeLast(2))
        assertEquals("/n/libproot-loader.so", cmd.environment["PROOT_LOADER"])
        assertFalse(cmd.environment.containsKey("PROOT_NO_SECCOMP"))
        val slow = ProotCommand.build("/p", "/l", "/t", "/r", listOf("true"), seccompAcceleration = false)
        assertEquals("1", slow.environment["PROOT_NO_SECCOMP"])
    }

    @Test fun launchArgsUseVerifiedFlagsAndSeparateProfiles() {
        val spec = LaunchSpec(1280, 720, 1.5f, "https://example.com")
        val chrome = ChromeAdapter.launchArgs(spec)
        val brave = BraveAdapter.launchArgs(spec)
        for (args in listOf(chrome, brave)) {
            assertTrue(args.contains("--ozone-platform=x11"))
            assertTrue(args.contains("--no-sandbox"))
            // Hides the --no-sandbox warning bar over the top of the page.
            assertTrue(args.contains("--test-type"))
            assertTrue(args.contains("--window-size=1280,720"))
            assertTrue(args.contains("--force-device-scale-factor=1.50"))
            assertEquals("https://example.com", args.last())
        }
        assertTrue(chrome.contains("--user-data-dir=/home/autobridge/.autobridge/chrome/profile"))
        assertTrue(brave.contains("--user-data-dir=/home/autobridge/.autobridge/brave/profile"))
        assertTrue(chrome.contains("--disk-cache-dir=/home/autobridge/.autobridge/chrome/cache"))
    }

    @Test fun braveAndChromeSourcesPointAtTheirVendors() {
        assertTrue(ChromeAdapter.sourcesFile().contains("URIs: https://dl.google.com/linux/chrome/deb/"))
        assertTrue(ChromeAdapter.sourcesFile().contains("Signed-By: ${ChromeAdapter.keyringPath}"))
        assertTrue(BraveAdapter.sourcesFile().contains("URIs: https://brave-browser-apt-release.s3.brave.com/"))
        assertTrue(BraveAdapter.managedPolicy().getBoolean("BraveRewardsDisabled"))
        assertFalse(ChromeAdapter.managedPolicy().has("BraveRewardsDisabled"))
        assertFalse(ChromeAdapter.managedPolicy().has("NewTabPageLocation"))
        val withStart = BraveAdapter.managedPolicy("file:///opt/autobridge/start/index.html")
        assertEquals("file:///opt/autobridge/start/index.html", withStart.getString("NewTabPageLocation"))
        assertEquals("file:///opt/autobridge/start/index.html", withStart.getString("HomepageLocation"))
        assertTrue(withStart.getBoolean("BraveRewardsDisabled"))
    }

    @Test fun displayMappingLetterboxesAndInverts() {
        // 1280x720 display on a 1920x1200 phone surface: scale 1.5, 60 px bars top and bottom.
        val m = DisplayMapping(1280, 720, 1920, 1200)
        assertEquals(1.5f, m.scale)
        assertEquals(0f, m.offsetX)
        assertEquals(60f, m.offsetY)
        assertEquals(0 to 0, m.toDisplay(0f, 60f))
        assertEquals(640 to 360, m.toDisplay(960f, 600f))
        assertEquals(1279 to 719, m.toDisplay(5000f, 5000f))
        assertFalse(m.contains(10f, 10f))
        // Car stable area excludes a 100 px rail on the left.
        val car = DisplayMapping(1180, 720, 1280, 720, areaLeft = 100)
        assertEquals(100 to 0, car.toDisplay(200f, 0f))
    }

    @Test fun xwdHeaderParsesTheRealXvfbLayout() {
        // Header fields as dumped from Xvfb 21 on Debian 13 (1280x720x24, -fbdir).
        val b = ByteBuffer.allocate(100)
        val fields = intArrayOf(160, 7, 2, 24, 1280, 720, 0, 0, 32, 0, 32, 32, 5120, 4, 0xff0000, 0xff00, 0xff, 8, 256, 256, 1280, 720, 0, 0, 0)
        fields.forEach { b.putInt(it) }
        val header = XwdFramebuffer.parseHeader(b)
        assertEquals(1280, header.width)
        assertEquals(720, header.height)
        assertEquals(160 + 256 * 12, header.pixelOffset)
        assertEquals(3_689_632, header.pixelOffset + header.pixelBytes) // the real file's size
    }

    @Test fun keysymsForText() {
        assertEquals('a'.code, Keysym.forCodePoint('a'.code))
        assertEquals(Keysym.RETURN, Keysym.forCodePoint('\n'.code))
        assertEquals(0x01000e01, Keysym.forCodePoint(0x0e01)) // ก
    }

    @Test fun downloaderHashMatchesKnownValue() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            VerifiedDownloader.sha256(java.io.ByteArrayInputStream(ByteArray(0))),
        )
        assertEquals(64, RuntimeSource.LAYER_SHA256.length)
        assertTrue(RuntimeSource.LAYER_URL.endsWith(RuntimeSource.LAYER_SHA256))
    }
}
