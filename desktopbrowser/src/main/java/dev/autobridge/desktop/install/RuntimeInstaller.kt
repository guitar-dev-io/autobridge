package dev.autobridge.desktop.install

import android.content.Context
import android.os.StatFs
import dev.autobridge.desktop.DesktopLog
import dev.autobridge.desktop.runtime.GuestNetworkConfig
import dev.autobridge.desktop.runtime.LinuxRuntime
import dev.autobridge.desktop.runtime.RuntimeLayout
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * Installs the shared Linux runtime once: download → verify → extract → configure → base packages.
 *
 * Atomic by construction: the archive is verified before it is opened, extracted into `staging/`,
 * and renamed into place only when extraction finished; the completion marker is written after
 * the base packages are in. An interrupted install therefore leaves either nothing, a staging
 * directory (deleted on the next attempt) or a rootfs without a marker (re-configured on retry,
 * no new download).
 */
class RuntimeInstaller(
    private val context: Context,
    private val runtime: LinuxRuntime,
    private val downloader: VerifiedDownloader,
    private val store: EngineStore,
) {
    private val layout: RuntimeLayout get() = runtime.layout

    fun install(report: (ComponentState) -> Unit, isCancelled: () -> Boolean) {
        if (!RuntimeLayout.deviceSupported()) throw IOException("This phone is not arm64; Linux Chrome and Brave need arm64.")
        if (!runtime.binariesPresent()) throw IOException("PRoot is missing from this build (arm64 sideload builds only).")

        if (!layout.rootfs.isDirectory || !File(layout.rootfs, "usr/bin").isDirectory) {
            ensureFreeSpace(RuntimeSource.REQUIRED_FREE_BYTES)
            val archive = File(layout.downloads, "debian-${RuntimeSource.LAYER_SHA256.take(12)}.tar.gz")
            if (!(archive.isFile && archive.length() == RuntimeSource.LAYER_SIZE_BYTES)) {
                report(ComponentState(InstallState.DOWNLOADING, 0f, "Debian ${RuntimeSource.DEBIAN_VERSION}", bytesTotal = RuntimeSource.LAYER_SIZE_BYTES))
                val token = downloader.dockerHubToken(RuntimeSource.REGISTRY_REPOSITORY)
                downloader.download(
                    url = RuntimeSource.LAYER_URL,
                    target = archive,
                    expectedSha256 = null, // verified below, as its own visible step
                    headers = mapOf("Authorization" to "Bearer $token"),
                    isCancelled = isCancelled,
                ) { done, total ->
                    val size = if (total > 0) total else RuntimeSource.LAYER_SIZE_BYTES
                    report(ComponentState(InstallState.DOWNLOADING, done.toFloat() / size, "Debian ${RuntimeSource.DEBIAN_VERSION}", bytesDone = done, bytesTotal = size))
                }
            }

            report(ComponentState(InstallState.VERIFYING, null, "SHA-256"))
            val actual = VerifiedDownloader.sha256(archive)
            if (actual != RuntimeSource.LAYER_SHA256) {
                archive.delete()
                throw VerifiedDownloader.IntegrityException("Runtime archive failed verification (got $actual)")
            }

            report(ComponentState(InstallState.EXTRACTING, 0f))
            SafeFiles.deleteTree(layout.staging)
            val stagingRoot = File(layout.staging, "rootfs")
            val extractor = SafeTarExtractor(isCancelled) { bytes ->
                report(ComponentState(InstallState.EXTRACTING, (bytes.toFloat() / ESTIMATED_UNPACKED).coerceAtMost(0.99f)))
            }
            GZIPInputStream(archive.inputStream().buffered(256 * 1024), 64 * 1024).use { extractor.extract(it, stagingRoot) }
            SafeFiles.deleteTree(layout.rootfs)
            if (!stagingRoot.renameTo(layout.rootfs)) throw IOException("Could not move the runtime into place")
            SafeFiles.deleteTree(layout.staging)
            archive.delete()
        }

        report(ComponentState(InstallState.CONFIGURING, 0f, "Preparing Debian"))
        writeConfiguration()
        runtime.seccompAcceleration = store.seccompAcceleration

        val apt = AptRunner(runtime, layout)
        // A previous attempt may have been killed inside dpkg (app killed, phone rebooted).
        apt.recover(isCancelled)
        report(ComponentState(InstallState.CONFIGURING, 0.05f, "apt-get update"))
        apt.update(isCancelled)
        apt.install(RuntimeSource.BASE_PACKAGES, isCancelled) { status ->
            val fraction = 0.1f + 0.9f * status.percent / 100f
            report(ComponentState(InstallState.CONFIGURING, fraction, status.message))
        }

        val glibc = runCatching { runtime.output(listOf("/usr/bin/ldd", "--version")).lineSequence().first() }.getOrNull()
        store.glibcVersion = glibc
        layout.completeMarker.writeText("${RuntimeSource.MANIFEST_DIGEST}\n${RuntimeSource.VERSION_LABEL}\n${System.currentTimeMillis()}\n")
        store.runtimeVersion = RuntimeSource.VERSION_LABEL
        store.runtimeError = null
        DesktopLog.i("runtime ready: ${RuntimeSource.VERSION_LABEL}; $glibc")
    }

    /** Files the guest needs that a container image normally gets from its host. */
    fun writeConfiguration() {
        val root = layout.rootfs
        File(root, "etc").mkdirs()
        GuestNetworkConfig.refresh(context, root)
        File(root, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        File(root, "etc/apt/apt.conf.d").mkdirs()
        File(root, "etc/apt/apt.conf.d/99autobridge").writeText(
            """
            // Written by AutoBridge. apt cannot drop to the _apt user under PRoot's fake root.
            APT::Sandbox::User "root";
            APT::Install-Recommends "false";
            APT::Install-Suggests "false";
            Acquire::Retries "3";
            Dpkg::Use-Pty "0";
            Dpkg::Options { "--force-unsafe-io"; "--force-confdef"; "--force-confold"; };
            """.trimIndent() + "\n"
        )
        File(root, "etc/autobridge").mkdirs()
        context.assets.open("desktop/pulse.pa").use { input -> File(root, "etc/autobridge/pulse.pa").outputStream().use { input.copyTo(it) } }
        for (guest in listOf(RuntimeLayout.GUEST_HOME, RuntimeLayout.GUEST_RUN_DIR, RuntimeLayout.GUEST_FB_DIR, RuntimeLayout.GUEST_PULSE_DIR, RuntimeLayout.GUEST_X11_DIR, "/root", "/tmp")) {
            layout.host(guest).mkdirs()
        }
        layout.host("/tmp").setWritable(true, false)
        layout.host(RuntimeLayout.GUEST_X11_DIR).setWritable(true, false)
    }

    private fun ensureFreeSpace(required: Long) {
        layout.base.mkdirs()
        val free = StatFs(layout.base.path).availableBytes
        if (free < required) {
            throw IOException("Not enough storage: ${required / MB} MB needed, ${free / MB} MB free")
        }
    }

    companion object {
        private const val MB = 1024 * 1024
        /** Unpacked size of the pinned layer (its tar unpacks to 100.6 MB); only used to scale the progress bar. */
        private const val ESTIMATED_UNPACKED = 96f * 1024 * 1024
    }
}

/** apt-get inside the runtime, with progress parsed from its status fd. */
class AptRunner(private val runtime: LinuxRuntime, private val layout: RuntimeLayout) {
    private val env = mapOf("DEBIAN_FRONTEND" to "noninteractive", "DEBCONF_NONINTERACTIVE_SEEN" to "true")
    private val log get() = layout.logFile("apt")

    fun update(isCancelled: () -> Boolean) {
        val tail = ArrayDeque<String>()
        val code = runtime.run(listOf("apt-get", "update", "-q"), env, log, isCancelled = isCancelled) { remember(tail, it) }
        if (code != 0) throw IOException("apt-get update failed ($code): ${tail.joinToString(" | ")}")
    }

    /** Finishes whatever an interrupted dpkg run left half-configured; harmless when nothing is. */
    fun recover(isCancelled: () -> Boolean) {
        runtime.run(listOf("/bin/sh", "-c", "dpkg --configure -a < /dev/null"), env, log, isCancelled = isCancelled)
        runtime.run(listOf("/bin/sh", "-c", "apt-get -q -y -f install < /dev/null"), env, log, isCancelled = isCancelled)
    }

    fun install(packages: List<String>, isCancelled: () -> Boolean, onStatus: (AptStatus) -> Unit) =
        aptGet(listOf("install", "-y", "--no-install-recommends") + packages, isCancelled, onStatus)

    fun upgrade(pkg: String, isCancelled: () -> Boolean, onStatus: (AptStatus) -> Unit) =
        aptGet(listOf("install", "-y", "--only-upgrade", pkg), isCancelled, onStatus)

    fun purge(pkg: String, isCancelled: () -> Boolean) {
        aptGet(listOf("purge", "-y", pkg), isCancelled) {}
        aptGet(listOf("autoremove", "-y", "--purge"), isCancelled) {}
    }

    /** (installed, candidate) for [pkg]. */
    fun policy(pkg: String): Pair<String?, String?> =
        AptStatus.parsePolicy(runtime.output(listOf("apt-cache", "policy", pkg), env))

    fun installedVersion(pkg: String): String? = runCatching {
        runtime.output(listOf("dpkg-query", "-W", "-f=\${Version}", pkg)).trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * apt's machine-readable progress goes to its own file, never to stdout: apt makes its status
     * descriptor non-blocking, and with `Status-Fd=1` that is the very pipe the maintainer scripts
     * write to, so their `echo`s failed with "I/O error" whenever the pipe was momentarily full
     * (seen on the emulator: ca-certificates, fontconfig and gstreamer postinsts all failed).
     */
    private fun aptGet(args: List<String>, isCancelled: () -> Boolean, onStatus: (AptStatus) -> Unit) {
        val tail = ArrayDeque<String>()
        val statusGuest = "${RuntimeLayout.GUEST_RUN_DIR}/apt-status"
        val statusHost = layout.host(statusGuest)
        statusHost.parentFile?.mkdirs()
        statusHost.writeText("")
        val command = listOf(
            "/bin/sh", "-c", "exec 3>>$statusGuest; exec apt-get -q -o APT::Status-Fd=3 \"\$@\" < /dev/null", "sh",
        ) + args
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val follower = Thread {
            var offset = 0L
            while (true) {
                val finished = done.get()
                runCatching {
                    java.io.RandomAccessFile(statusHost, "r").use { raf ->
                        if (raf.length() > offset) {
                            val bytes = ByteArray((raf.length() - offset).toInt())
                            raf.seek(offset)
                            raf.readFully(bytes)
                            // Only whole lines; a line apt is still writing is read next time.
                            val end = bytes.lastIndexOf('\n'.code.toByte())
                            if (end >= 0) {
                                String(bytes, 0, end, Charsets.UTF_8).lineSequence()
                                    .mapNotNull(AptStatus::parse)
                                    .forEach(onStatus)
                                offset += end + 1
                            }
                        }
                    }
                }
                if (finished) break
                Thread.sleep(250)
            }
        }.apply { name = "apt-status"; isDaemon = true; start() }
        val code = try {
            runtime.run(command, env, log, isCancelled = isCancelled) { line -> remember(tail, line) }
        } finally {
            done.set(true)
            follower.join(2000)
        }
        if (code != 0) throw IOException("apt-get ${args.first()} failed ($code): ${tail.joinToString(" | ")}")
    }

    private fun remember(tail: ArrayDeque<String>, line: String) {
        if (line.isBlank()) return
        tail.addLast(line.take(200))
        while (tail.size > 4) tail.removeFirst()
    }
}
