package dev.autobridge.projection

import android.content.Context
import dev.autobridge.logging.StructuredLog
import dev.autobridge.input.ShizukuCommandRunner
import dev.autobridge.input.ShizukuInputBackend
import dev.autobridge.install.InstallerSource
import rikka.shizuku.Shizuku

/**
 * Makes a sideloaded AutoBridge build visible on the Android Auto car screen by rewriting its
 * recorded installer to the Play Store (see [InstallerSource] for why Android Auto needs this).
 *
 * Projection-flavor only. The safe (Play) build has no projection source set, so none of this is
 * compiled into a store release - a store build is already installed by the Play Store and needs
 * no spoof.
 *
 * Two privilege paths, tried in order so no root is required:
 * - root: run the command through `su` when a root shell is present.
 * - Shizuku: run it in the Shizuku shell-UID process (non-root, via wireless debugging).
 *
 * Nothing is forged: the already-installed APK is reinstalled in place with the Play Store named
 * as its install source. The APK is not modified or re-signed - only the recorded install source
 * changes, for an app the user installed themselves.
 */
object InstallerSpoofController {
    private const val TAG = "PROJECTION"

    enum class Method { ROOT, SHIZUKU }

    sealed interface Result {
        /** Already recorded as the Play Store; nothing to do. */
        object AlreadyTrusted : Result
        data class Success(val method: Method) : Result
        /** Neither root nor a granted Shizuku connection is available to run the command. */
        object NoPrivilege : Result
        data class Failed(val detail: String) : Result
    }

    fun currentInstaller(context: Context): String? =
        runCatching {
            context.packageManager.getInstallerPackageName(context.packageName)
        }.getOrNull()

    fun isAlreadyVisible(context: Context): Boolean = InstallerSource.isTrusted(currentInstaller(context))

    /** True when a path exists to perform the spoof, so the UI can offer the action. */
    fun canAttempt(): Boolean = hasRoot() || ShizukuInputBackend.isPermissionGranted

    /**
     * Rewrites the installer, preferring root, then Shizuku. Verifies by reading the installer
     * back through the package manager rather than trusting the command's exit, so a silent
     * failure is reported as [Result.Failed] rather than a false success.
     */
    fun makeVisible(context: Context): Result {
        val target = context.packageName
        if (isAlreadyVisible(context)) return Result.AlreadyTrusted

        val method = when {
            hasRoot() -> Method.ROOT
            ShizukuInputBackend.isPermissionGranted && Shizuku.pingBinder() -> Method.SHIZUKU
            else -> return Result.NoPrivilege
        }

        val apkPath = context.applicationInfo.sourceDir
            ?: return Result.Failed("could not resolve own APK path")
        val installArgs = InstallerSource.installWithPlayStoreSourceCommand(apkPath)
        // Longer timeout than a plain command: this is a reinstall of the whole package.
        val ran = when (method) {
            Method.ROOT -> runAsRoot(installArgs, timeoutMs = 60_000L)
            Method.SHIZUKU -> {
                ShizukuInputBackend.bind(context)
                ShizukuInputBackend.runShellCommand(installArgs, timeoutMs = 60_000L) != null
            }
        }
        if (!ran) return Result.Failed("reinstall via $method did not run")

        // The command can report success even when the manager quietly ignored it, so confirm
        // against the recorded value.
        val now = currentInstaller(context)
        StructuredLog.i(TAG, "installer spoof via $method -> recorded=$now")
        return if (InstallerSource.isTrusted(now)) Result.Success(method) else Result.Failed("installer still $now")
    }

    private fun hasRoot(): Boolean =
        ShizukuCommandRunner.capture(listOf("su", "-c", "id"), timeoutMs = 3_000L)?.contains("uid=0") == true

    private fun runAsRoot(args: List<String>, timeoutMs: Long): Boolean =
        ShizukuCommandRunner.capture(listOf("su", "-c", args.joinToString(" ")), timeoutMs = timeoutMs) != null
}
