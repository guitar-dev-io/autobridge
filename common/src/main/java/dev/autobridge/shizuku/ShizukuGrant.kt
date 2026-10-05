package dev.autobridge.shizuku

import android.content.pm.PackageManager
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
    val isGranted: Boolean
        get() = Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
}
