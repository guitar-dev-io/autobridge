package dev.autobridge.desktop.install

import dev.autobridge.desktop.DesktopLog
import dev.autobridge.desktop.browser.DesktopBrowserAdapter
import dev.autobridge.desktop.runtime.LinuxRuntime
import dev.autobridge.desktop.runtime.RuntimeLayout
import java.io.File
import java.io.IOException

/**
 * Install, update and remove one browser inside the shared runtime, from its vendor's own APT
 * repository. The repository is trusted only after its signing key matched the fingerprints
 * pinned in the adapter; from then on apt verifies every Release file and .deb against it.
 */
class BrowserPackages(private val runtime: LinuxRuntime, private val downloader: VerifiedDownloader) {
    private val layout: RuntimeLayout get() = runtime.layout
    private val apt = AptRunner(runtime, layout)

    fun isInstalled(adapter: DesktopBrowserAdapter): Boolean = layout.host(adapter.executable).isFile

    fun install(adapter: DesktopBrowserAdapter, report: (ComponentState) -> Unit, isCancelled: () -> Boolean): String {
        // apt fails half-way (and Android starts killing apps) when the disk fills mid-install.
        val required = adapter.estimatedInstalledBytes + adapter.estimatedDownloadBytes + FREE_MARGIN
        val free = android.os.StatFs(layout.base.path).availableBytes
        if (free < required) {
            throw IOException("Not enough storage for ${adapter.displayName}: ${required / MB} MB needed, ${free / MB} MB free")
        }
        report(ComponentState(InstallState.VERIFYING, null, "Signing key"))
        val key = downloader.fetchBytes(adapter.keyUrl, limit = 256 * 1024)
        val found = OpenPgpKeys.primaryFingerprints(key)
        val pinned = adapter.pinnedFingerprints.map(OpenPgpKeys::normalize).toSet()
        if (found.isEmpty() || !found.all { it in pinned }) {
            throw VerifiedDownloader.IntegrityException(
                "${adapter.displayName} signing key does not match the published fingerprint (got ${found.joinToString()})"
            )
        }
        writeGuest(adapter.keyringPath, key)
        writeGuest("/etc/apt/sources.list.d/${adapter.sourcesFileName}", adapter.sourcesFile().toByteArray())

        report(ComponentState(InstallState.CONFIGURING, 0.02f, "apt-get update"))
        apt.recover(isCancelled)
        apt.update(isCancelled)
        apt.install(listOf(adapter.aptPackage), isCancelled) { status -> report(stateFor(status)) }
        if (!isInstalled(adapter)) throw IOException("${adapter.aptPackage} installed but ${adapter.executable} is missing")

        writePolicy(adapter)
        layout.host(adapter.profileDir()).mkdirs()
        layout.host(adapter.cacheDir()).mkdirs()
        val version = apt.installedVersion(adapter.aptPackage) ?: "?"
        DesktopLog.i("${adapter.id.key} installed: $version")
        return version
    }

    /** apt-installs [names] (already checked by [PackageNames]) into the shared runtime. */
    fun installPackages(names: List<String>, report: (ComponentState) -> Unit, isCancelled: () -> Boolean) {
        val required = FREE_MARGIN + 100L * MB
        val free = android.os.StatFs(layout.base.path).availableBytes
        if (free < required) throw IOException("Not enough storage: ${required / MB} MB needed, ${free / MB} MB free")
        report(ComponentState(InstallState.CONFIGURING, 0.02f, "apt-get update"))
        apt.recover(isCancelled)
        apt.update(isCancelled)
        apt.install(names, isCancelled) { status -> report(stateFor(status)) }
        report(ComponentState(InstallState.CONFIGURING, 0.97f, "fc-cache"))
        // New fonts are only seen by the browser once fontconfig has re-read its directories.
        runtime.run(listOf("fc-cache", "-f"), emptyMap(), layout.logFile("apt"), isCancelled = isCancelled)
    }

    /** The fonts that stop emoji and other scripts showing as boxes, when the runtime lacks them. */
    fun missingFontPackages(): List<String> = FONT_FILES.filterValues { !layout.host(it).isFile }.keys.toList()

    /** Refreshes the repository and returns (installed, candidate). */
    fun checkUpdate(adapter: DesktopBrowserAdapter, isCancelled: () -> Boolean): Pair<String?, String?> {
        apt.update(isCancelled)
        return apt.policy(adapter.aptPackage)
    }

    fun update(adapter: DesktopBrowserAdapter, report: (ComponentState) -> Unit, isCancelled: () -> Boolean): String {
        apt.update(isCancelled)
        apt.upgrade(adapter.aptPackage, isCancelled) { status -> report(stateFor(status)) }
        writePolicy(adapter)
        return apt.installedVersion(adapter.aptPackage) ?: "?"
    }

    fun remove(adapter: DesktopBrowserAdapter, keepProfile: Boolean, isCancelled: () -> Boolean) {
        if (isInstalled(adapter)) apt.purge(adapter.aptPackage, isCancelled)
        for (guest in listOf(adapter.keyringPath, "/etc/apt/sources.list.d/${adapter.sourcesFileName}", "${adapter.policyDir}/autobridge.json") + adapter.extraRepoFiles) {
            layout.host(guest).delete()
        }
        if (!keepProfile) {
            SafeFiles.deleteTree(layout.host(adapter.profileDir()).parentFile!!)
        }
    }

    fun clearCache(adapter: DesktopBrowserAdapter) {
        SafeFiles.deleteTree(layout.host(adapter.cacheDir()))
        layout.host(adapter.cacheDir()).mkdirs()
    }

    /** Moves the profile aside (one backup kept) so the next launch starts clean. */
    fun resetProfile(adapter: DesktopBrowserAdapter) {
        val profile = layout.host(adapter.profileDir())
        val backup = File(profile.parentFile, "profile.bak")
        SafeFiles.deleteTree(backup)
        if (profile.exists() && !profile.renameTo(backup)) throw IOException("Could not move the old profile aside")
        profile.mkdirs()
    }

    fun installedBytes(adapter: DesktopBrowserAdapter): Long = SafeFiles.sizeOf(layout.host(adapter.installDir))
    fun profileBytes(adapter: DesktopBrowserAdapter): Long = SafeFiles.sizeOf(layout.host(adapter.profileDir()))
    fun cacheBytes(adapter: DesktopBrowserAdapter): Long = SafeFiles.sizeOf(layout.host(adapter.cacheDir()))

    private fun writePolicy(adapter: DesktopBrowserAdapter) {
        writeGuest("${adapter.policyDir}/autobridge.json", adapter.managedPolicy().toString(2).toByteArray())
    }

    private fun writeGuest(guestPath: String, bytes: ByteArray) {
        val file = layout.host(guestPath)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) throw IOException("Could not write $guestPath")
    }

    private fun stateFor(status: AptStatus): ComponentState = when (status.phase) {
        AptStatus.Phase.DOWNLOAD -> ComponentState(InstallState.DOWNLOADING, status.percent / 100f, status.message)
        AptStatus.Phase.INSTALL ->
            if (status.message.startsWith("Unpacking") || status.message.startsWith("Preparing")) {
                ComponentState(InstallState.EXTRACTING, status.percent / 100f, status.message)
            } else {
                ComponentState(InstallState.CONFIGURING, status.percent / 100f, status.message)
            }
        AptStatus.Phase.ERROR -> ComponentState(InstallState.CONFIGURING, status.percent / 100f, status.message, lastError = status.message)
    }

    private companion object {
        const val MB = 1024 * 1024
        /** Debian package to the file that proves it is installed. */
        val FONT_FILES = mapOf(
            "fonts-noto-color-emoji" to "/usr/share/fonts/truetype/noto/NotoColorEmoji.ttf",
            "fonts-noto-core" to "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf",
        )
        /** Android keeps a low-storage reserve and refuses app updates inside it. */
        const val FREE_MARGIN = 300L * 1024 * 1024
    }
}
