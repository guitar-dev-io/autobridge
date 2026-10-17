package dev.autobridge.shizuku

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import rikka.shizuku.Shizuku

/**
 * Whether Shizuku is running and has granted this app its permission.
 *
 * One definition, because the grant is one piece of OS state and more than one feature reads it:
 * [dev.autobridge.input.ShizukuInputBackend] gates the touch backend on it, and Duo Screen's
 * privileged ops gate pane launches and touch injection on the same grant rather than asking for
 * a second one. Those two live in different Gradle modules, which is why the check sits here
 * rather than in either of them.
 *
 * This is only the grant. Whether a *feature* may use it is a separate question that each caller
 * answers for itself — the touch backend, for one, also requires its FeaturePolicy entries.
 */
object ShizukuGrant {
    /** Shizuku's own package, the one the manifest's `<queries>` makes visible. */
    const val PACKAGE = "moe.shizuku.privileged.api"

    /**
     * Where Shizuku stands, in the order a user has to fix it: installed, then started (it stops
     * at every reboot), then this app allowed. Each one is a different thing to tell them.
     */
    enum class State { NOT_INSTALLED, NOT_RUNNING, NOT_GRANTED, READY }

    val isGranted: Boolean
        get() = runCatching {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    fun state(context: Context): State {
        val installed = runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = running && isGranted
        return stateOf(installed, running, granted)
    }

    /**
     * The pure decision behind [state]. A running Shizuku counts as installed whatever the package
     * query says: a fork under another package name still serves the same binder.
     */
    fun stateOf(installed: Boolean, running: Boolean, granted: Boolean): State = when {
        granted -> State.READY
        running -> State.NOT_GRANTED
        installed -> State.NOT_RUNNING
        else -> State.NOT_INSTALLED
    }

    /** Opens Shizuku, to press Start in it; false when it cannot be opened. */
    fun openApp(context: Context): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        return runCatching {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    /** Opens Shizuku's store page: the Play Store app when there is one, the web page otherwise. */
    fun openInstallPage(context: Context): Boolean {
        val pages = listOf(
            Uri.parse("market://details?id=$PACKAGE"),
            Uri.parse("https://play.google.com/store/apps/details?id=$PACKAGE")
        )
        return pages.any { page ->
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
        }
    }
}
