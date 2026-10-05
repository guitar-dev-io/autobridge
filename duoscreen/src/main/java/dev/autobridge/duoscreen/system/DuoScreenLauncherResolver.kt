package dev.autobridge.duoscreen.system

/**
 * Pure logic that turns a package name plus the launcher-activity candidates PackageManager
 * reported into the one explicit launcher activity to launch, isolated from the framework calls
 * that produced the candidates so it can be unit-tested on the JVM (mirrors
 * [dev.autobridge.apps.InstalledAppRepository.prepare]).
 *
 * Returns the matched [LauncherActivity] rather than an `android.content.ComponentName` so the
 * logic stays JVM-testable: `ComponentName`'s accessors return unmocked defaults under the unit
 * test runtime, so the caller builds the `ComponentName` from the returned pair instead.
 *
 * Needed because an *implicit* MAIN/LAUNCHER + setPackage launch fails to resolve on this device's
 * MIUI build for a package whose launcher activity is "enabled by default but not yet explicitly
 * enabled", leaving a blank pane; launching the explicit component instead works. See
 * [DuoScreenShizukuOps.launchOnDisplay].
 */
object DuoScreenLauncherResolver {
    /** A launcher activity PackageManager resolved, reduced to the two fields a launch needs. */
    data class LauncherActivity(val packageName: String, val activityName: String)

    /**
     * Returns the launcher activity to launch for [packageName], or null when none of [candidates]
     * belongs to it. Picks the first matching candidate so `queryIntentActivities`' best-match
     * ordering is preserved.
     */
    fun explicitActivityOrNull(
        packageName: String,
        candidates: List<LauncherActivity>
    ): LauncherActivity? =
        candidates.firstOrNull { it.packageName == packageName }
}
