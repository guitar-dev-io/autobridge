package dev.autobridge.install

/**
 * Pure logic for the Android Auto installer-source requirement, kept free of Android types so each
 * decision is unit-testable.
 *
 * Android Auto only lists an app on the car screen when the package manager records its installer
 * as the Play Store (`com.android.vending`). A sideloaded build (adb, a file manager, a browser
 * download) has a different installer or none, so Android Auto hides it.
 *
 * The recorded installer cannot simply be rewritten afterwards: `pm set-installer` requires the
 * caller to share a signing certificate with the new installer package, which no shell or root
 * command can satisfy for `com.android.vending`. What does work is setting the installer at
 * install time with `pm install -i`, which carries no such check. So the fix reinstalls the
 * already-present APK in place through a privileged shell, naming the Play Store as the installer.
 * This is the same effect KingInstaller and the Fermata installers achieve; nothing is re-signed.
 */
object InstallerSource {
    const val PLAY_STORE = "com.android.vending"

    /** True when [installer] already satisfies Android Auto's store-origin check. */
    fun isTrusted(installer: String?): Boolean = installer == PLAY_STORE

    /**
     * Reinstalls [apkPath] in place, recording the Play Store as the installer. `-r` keeps the
     * existing app and data (it is the same APK, already installed); `-i` sets the installer that
     * Android Auto checks. The APK path is the package's own `base.apk`, which the shell UID can
     * read, so nothing has to be copied out first.
     */
    fun installWithPlayStoreSourceCommand(apkPath: String): List<String> =
        listOf("pm", "install", "-i", PLAY_STORE, "-r", apkPath)

    /** The command that reads back the recorded installer, so success can be verified. */
    fun readInstallerCommand(targetPackage: String): List<String> =
        listOf("pm", "list", "packages", "-i", targetPackage)

    /**
     * Parses `pm list packages -i <pkg>` output into the recorded installer. The line looks like
     * `package:dev.autobridge  installer=com.android.vending`; the installer token can be absent
     * or the literal `null` for a sideloaded package. Returns null when no installer is recorded.
     */
    fun parseInstaller(output: String?): String? {
        if (output.isNullOrBlank()) return null
        val token = output.lineSequence()
            .mapNotNull { line -> INSTALLER.find(line)?.groupValues?.get(1)?.trim() }
            .firstOrNull()
        return token?.takeIf { it.isNotEmpty() && it != "null" }
    }

    private val INSTALLER = Regex("installer=(\\S+)")
}
