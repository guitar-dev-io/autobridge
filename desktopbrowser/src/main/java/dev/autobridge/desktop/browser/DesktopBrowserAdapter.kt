package dev.autobridge.desktop.browser

import dev.autobridge.desktop.runtime.RuntimeLayout
import org.json.JSONObject

/** The browsers the engine knows. [key] is the stable id used in storage and guest paths. */
enum class DesktopBrowserId(val key: String) {
    CHROME("chrome"),
    BRAVE("brave");

    companion object {
        fun fromKey(key: String?): DesktopBrowserId? = entries.firstOrNull { it.key == key }
    }
}

/** What a session asks of the browser it launches. */
data class LaunchSpec(
    val width: Int,
    val height: Int,
    val scale: Float,
    val startUrl: String?,
)

/**
 * Everything browser-specific, and nothing else. The runtime, display, input and audio bridges
 * never look at which browser is running; adding another Chromium-based browser is one more
 * implementation of this interface.
 */
interface DesktopBrowserAdapter {
    val id: DesktopBrowserId
    val displayName: String
    /** Debian package installed from the vendor's repository. */
    val aptPackage: String
    /** deb822 `.sources` file contents for the vendor's repository; [keyringPath] is its key. */
    val sourcesFileName: String
    val keyUrl: String
    /** Primary key fingerprints published by the vendor; the fetched key file must match. */
    val pinnedFingerprints: Set<String>
    val keyringPath: String
    /** The real binary (not the shell wrapper), checked to decide whether the browser is installed. */
    val executable: String
    /** The vendor's wrapper script, which sets up the binary's environment; this is what runs. */
    val launcher: String
    /** Install root inside the guest, measured for "installed size". */
    val installDir: String
    /** Managed-policy directory read by this browser. */
    val policyDir: String
    /** Files the vendor package may add on its own (its postinst rewrites the repo entry). */
    val extraRepoFiles: List<String>
    /** Approximate download and installed size, measured in an arm64 container (2026-10-18). */
    val estimatedDownloadBytes: Long
    val estimatedInstalledBytes: Long

    fun sourcesFile(): String

    fun profileDir(): String = "${RuntimeLayout.GUEST_HOME}/.autobridge/${id.key}/profile"
    fun cacheDir(): String = "${RuntimeLayout.GUEST_HOME}/.autobridge/${id.key}/cache"

    /**
     * Managed policy written at install. Keys a browser does not know are ignored by it, so the
     * shared ones can sit next to vendor-specific ones.
     */
    fun managedPolicy(): JSONObject = JSONObject()
        .put("PasswordManagerEnabled", false)
        .put("DefaultBrowserSettingEnabled", false)
        .put("MetricsReportingEnabled", false)
        .put("BackgroundModeEnabled", false)
        .put("PromotionsEnabled", false)

    /**
     * Command line. Every flag here was checked against the real binary (see
     * docs/desktop-browser/TEST-RESULTS.md); none is copied blindly:
     * - `--ozone-platform=x11`: the display is Xvfb; never let Ozone guess Wayland.
     * - `--no-sandbox`: REQUIRED under PRoot. Chromium's sandbox needs user namespaces or a setuid
     *   helper, neither of which an Android app can have. Renderer processes are therefore only
     *   contained by the Android app sandbox around AutoBridge. The browser shows a warning bar
     *   about it, which is left visible on purpose.
     * - `--disable-dev-shm-usage`: shared memory goes to /tmp instead of a tiny emulated /dev/shm.
     * - `--disable-gpu`: there is no GPU driver inside the runtime; Chromium uses SwiftShader.
     * - `--password-store=basic`: there is no keyring daemon.
     * - `--touch-events=enabled`: pages may enable touch-specific UI; input itself arrives as
     *   pointer events (XTEST), which every page handles.
     */
    fun launchArgs(spec: LaunchSpec): List<String> = buildList {
        add("--ozone-platform=x11")
        add("--user-data-dir=${profileDir()}")
        add("--disk-cache-dir=${cacheDir()}")
        add("--no-first-run")
        add("--no-default-browser-check")
        add("--password-store=basic")
        add("--no-sandbox")
        add("--disable-dev-shm-usage")
        add("--disable-gpu")
        add("--touch-events=enabled")
        add("--window-position=0,0")
        add("--window-size=${spec.width},${spec.height}")
        add("--force-device-scale-factor=${"%.2f".format(java.util.Locale.US, spec.scale)}")
        addAll(extraArgs())
        spec.startUrl?.let(::add)
    }

    fun extraArgs(): List<String> = emptyList()
}

object ChromeAdapter : DesktopBrowserAdapter {
    override val id = DesktopBrowserId.CHROME
    override val displayName = "Google Chrome"
    override val aptPackage = "google-chrome-stable"
    // The same name Chrome's own postinst uses, so its rewrite replaces this file rather than
    // adding a second entry for the same repository.
    override val sourcesFileName = "google-chrome.sources"
    override val keyUrl = "https://dl.google.com/linux/linux_signing_key.pub"
    // Published at https://www.google.com/linuxrepositories/ ("Google Inc. (Linux Packages Signing
    // Authority)"). The obsolete 4CCA1EAF… key is deliberately not accepted.
    override val pinnedFingerprints = setOf("EB4C1BFD4F042F6DDDCCEC917721F63BD38B4796")
    override val keyringPath = "/usr/share/keyrings/autobridge-google-chrome.asc"
    override val executable = "/opt/google/chrome/chrome"
    override val launcher = "/opt/google/chrome/google-chrome"
    override val installDir = "/opt/google/chrome"
    override val policyDir = "/etc/opt/chrome/policies/managed"
    override val extraRepoFiles = listOf(
        "/etc/apt/sources.list.d/google-chrome.list",
        "/usr/share/keyrings/google-chrome.gpg",
        "/etc/default/google-chrome",
    )
    override val estimatedDownloadBytes = 136L * 1024 * 1024 + 60L * 1024 * 1024
    override val estimatedInstalledBytes = 507L * 1024 * 1024

    override fun sourcesFile(): String = """
        Types: deb
        URIs: https://dl.google.com/linux/chrome/deb/
        Suites: stable
        Components: main
        Architectures: arm64
        Signed-By: $keyringPath
    """.trimIndent() + "\n"
}

object BraveAdapter : DesktopBrowserAdapter {
    override val id = DesktopBrowserId.BRAVE
    override val displayName = "Brave"
    override val aptPackage = "brave-browser"
    override val sourcesFileName = "brave-browser-release.sources"
    override val keyUrl = "https://brave-browser-apt-release.s3.brave.com/brave-browser-archive-keyring.gpg"
    // The three "Brave Linux Release" keys in Brave's release-channel keyring, the same blocks
    // published at https://brave.com/signing-keys/ under "Linux package repositories, Release".
    override val pinnedFingerprints = setOf(
        "DBF1A116C220B8C7164F98230686B78420038257",
        "47D32A74E9A9E013A4B4926C68D513D36A73CD96",
        "B2A3DCA350E67256740DF904DE4EC67BE4B0DCA0",
    )
    override val keyringPath = "/usr/share/keyrings/brave-browser-archive-keyring.gpg"
    override val executable = "/opt/brave.com/brave/brave"
    override val launcher = "/opt/brave.com/brave/brave-browser"
    override val installDir = "/opt/brave.com/brave"
    override val policyDir = "/etc/brave/policies/managed"
    override val extraRepoFiles = listOf("/etc/apt/sources.list.d/brave-browser-release.list")
    override val estimatedDownloadBytes = 143L * 1024 * 1024 + 20L * 1024 * 1024
    override val estimatedInstalledBytes = 465L * 1024 * 1024

    override fun sourcesFile(): String = """
        Types: deb
        URIs: https://brave-browser-apt-release.s3.brave.com/
        Suites: stable
        Components: main
        Architectures: arm64
        Signed-By: $keyringPath
    """.trimIndent() + "\n"

    // Brave's extras that make no sense on a car screen. Shields (ad/tracker blocking) stay on.
    override fun managedPolicy(): JSONObject = super.managedPolicy()
        .put("BraveRewardsDisabled", true)
        .put("BraveWalletDisabled", true)
        .put("BraveVPNDisabled", true)
        .put("BraveAIChatEnabled", false)
        .put("TorDisabled", true)
}

object DesktopBrowsers {
    val all: List<DesktopBrowserAdapter> = listOf(ChromeAdapter, BraveAdapter)
    fun of(id: DesktopBrowserId): DesktopBrowserAdapter = all.first { it.id == id }
}
