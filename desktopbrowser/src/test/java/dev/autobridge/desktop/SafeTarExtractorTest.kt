package dev.autobridge.desktop

import dev.autobridge.desktop.install.SafeTarExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.GZIPInputStream

class SafeTarExtractorTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun extractsFilesDirectoriesSymlinksAndHardlinks() {
        val tar = TarBuilder()
            .dir("usr/")
            .dir("usr/bin/")
            .file("usr/bin/tool", "#!/bin/sh\necho hi\n", mode = 0b111_101_101)
            .symlink("bin", "usr/bin")
            .symlink("usr/bin/abs", "/etc/passwd")
            .hardlink("usr/bin/tool2", "usr/bin/tool")
            .build()
        val dest = tmp.newFolder("root")
        val stats = SafeTarExtractor().extract(ByteArrayInputStream(tar), dest)

        assertEquals(2, stats.directories)
        assertEquals(1, stats.files)
        assertEquals(2, stats.symlinks)
        assertEquals(1, stats.hardlinks)
        assertEquals("#!/bin/sh\necho hi\n", File(dest, "usr/bin/tool").readText())
        assertTrue(File(dest, "usr/bin/tool").canExecute())
        assertTrue(Files.isSymbolicLink(File(dest, "bin").toPath()))
        // Absolute links are kept as written (they mean guest paths) and never followed.
        assertEquals("/etc/passwd", Files.readSymbolicLink(File(dest, "usr/bin/abs").toPath()).toString())
        assertEquals("#!/bin/sh\necho hi\n", File(dest, "usr/bin/tool2").readText())
    }

    @Test fun rejectsParentReferences() {
        val tar = TarBuilder().file("../escape", "x").build()
        expectUnsafe(tar)
    }

    @Test fun rejectsWritingThroughASymlink() {
        val outside = tmp.newFolder("outside")
        val tar = TarBuilder()
            .symlink("evil", outside.absolutePath)
            .file("evil/planted", "x")
            .build()
        expectUnsafe(tar)
        assertFalse(File(outside, "planted").exists())
    }

    @Test fun absoluteNamesAreMadeRelative() {
        val dest = tmp.newFolder("root")
        SafeTarExtractor().extract(ByteArrayInputStream(TarBuilder().file("/etc/hostname", "car\n").build()), dest)
        assertEquals("car\n", File(dest, "etc/hostname").readText())
    }

    @Test fun gnuLongNamesAreSupported() {
        val long = "a/".repeat(70) + "file.txt"
        val dest = tmp.newFolder("root")
        SafeTarExtractor().extract(ByteArrayInputStream(TarBuilder().longName(long).file("ignored", "long").build()), dest)
        assertEquals("long", File(dest, long).readText())
    }

    @Test fun setIdBitsAreDropped() {
        val dest = tmp.newFolder("root")
        SafeTarExtractor().extract(ByteArrayInputStream(TarBuilder().file("su", "x", mode = 0b100_111_101_101).build()), dest)
        val perms = Files.getPosixFilePermissions(File(dest, "su").toPath(), LinkOption.NOFOLLOW_LINKS)
        assertEquals(7, perms.size) // rwxr-xr-x, no set-uid
    }

    @Test fun cancellationStopsExtraction() {
        val tar = TarBuilder().file("a", "1").file("b", "2").build()
        try {
            SafeTarExtractor(isCancelled = { true }).extract(ByteArrayInputStream(tar), tmp.newFolder("root"))
            fail("expected cancellation")
        } catch (_: SafeTarExtractor.InterruptedIOException) {
        }
    }

    /**
     * The pinned Debian layer itself. Runs when the archive is available locally:
     * `DESKTOP_ROOTFS_TAR=/path/to/layer.tar.gz ./gradlew :desktopbrowser:testDebugUnitTest`.
     */
    @Test fun extractsTheRealDebianLayer() {
        val path = System.getenv("DESKTOP_ROOTFS_TAR")
        assumeTrue(path != null && File(path).isFile)
        val dest = tmp.newFolder("rootfs")
        val stats = GZIPInputStream(File(path!!).inputStream().buffered()).use { SafeTarExtractor().extract(it, dest) }
        println("debian layer: $stats")
        assertTrue(File(dest, "usr/bin/apt-get").isFile)
        assertTrue(Files.isSymbolicLink(File(dest, "bin").toPath()))
        assertTrue(File(dest, "etc/debian_version").readText().startsWith("13"))
    }

    private fun expectUnsafe(tar: ByteArray) {
        try {
            SafeTarExtractor().extract(ByteArrayInputStream(tar), tmp.newFolder())
            fail("expected UnsafeEntryException")
        } catch (_: SafeTarExtractor.UnsafeEntryException) {
        }
    }

    /** Minimal ustar writer for tests. */
    private class TarBuilder {
        private val out = ByteArrayOutputStream()
        fun dir(name: String) = entry(name, '5', ByteArray(0), 0b111_101_101)
        fun file(name: String, text: String, mode: Int = 0b110_100_100) = entry(name, '0', text.toByteArray(), mode)
        fun symlink(name: String, target: String) = entry(name, '2', ByteArray(0), 0b111_111_111, target)
        fun hardlink(name: String, target: String) = entry(name, '1', ByteArray(0), 0b110_100_100, target)
        fun longName(name: String) = entry("././@LongLink", 'L', (name + "\u0000").toByteArray(), 0)

        fun build(): ByteArray { out.write(ByteArray(1024)); return out.toByteArray() }

        private fun entry(name: String, type: Char, data: ByteArray, mode: Int, link: String = ""): TarBuilder {
            val h = ByteArray(512)
            put(h, 0, name.take(100))
            put(h, 100, "%07o".format(mode))
            put(h, 108, "0000000"); put(h, 116, "0000000")
            put(h, 124, "%011o".format(data.size))
            put(h, 136, "%011o".format(0))
            h[156] = type.code.toByte()
            put(h, 157, link)
            put(h, 257, "ustar\u000000")
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            val sum = h.sumOf { it.toInt() and 0xff }
            put(h, 148, "%06o\u0000 ".format(sum))
            out.write(h)
            out.write(data)
            val pad = (512 - data.size % 512) % 512
            out.write(ByteArray(pad))
            return this
        }

        private fun put(h: ByteArray, at: Int, s: String) {
            val b = s.toByteArray()
            System.arraycopy(b, 0, h, at, b.size)
        }
    }
}
