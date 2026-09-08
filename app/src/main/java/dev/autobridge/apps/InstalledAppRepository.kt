package dev.autobridge.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build

data class InstalledApp(val packageName: String, val label: String)

/**
 * Launchable-app listing for the future Quick Apps launcher. Favorites, per-app landscape/
 * fullscreen profiles, and parked-only launching are separate, later roadmap items — this is
 * the data layer only.
 *
 * Querying `CATEGORY_LAUNCHER` activities via an `ACTION_MAIN`/`CATEGORY_LAUNCHER` intent is one
 * of Android's documented package-visibility exemptions, so this needs no `<queries>` manifest
 * entry or `QUERY_ALL_PACKAGES` permission.
 */
object InstalledAppRepository {
    fun listLaunchableApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = queryLaunchableActivities(pm, intent).mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            InstalledApp(packageName, info.loadLabel(pm).toString())
        }
        return prepare(apps, excludePackage = context.packageName)
    }

    /** Pure dedup/filter/sort, kept separate from the PackageManager glue above so it's unit-testable. */
    fun prepare(apps: List<InstalledApp>, excludePackage: String): List<InstalledApp> =
        apps
            .distinctBy { it.packageName }
            .filterNot { it.packageName == excludePackage }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })

    private fun queryLaunchableActivities(pm: PackageManager, intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
}
