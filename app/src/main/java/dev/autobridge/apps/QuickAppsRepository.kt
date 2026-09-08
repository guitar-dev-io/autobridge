package dev.autobridge.apps

import android.content.Context
import androidx.core.content.edit

/** Persisted Quick App record shown by phone and Android Auto launchers. */
data class QuickApp(
    val packageName: String,
    val label: String,
    val iconKey: String? = null,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val profileId: String = "default"
)

interface QuickAppsRepository {
    fun list(profileId: String = "default"): List<QuickApp>
    fun save(app: QuickApp)
    fun setEnabled(packageName: String, enabled: Boolean, profileId: String = "default")
    fun remove(packageName: String, profileId: String = "default")
}

/** Pure catalog operations shared by persistence and UI code. */
object QuickAppsCatalog {
    fun sort(apps: Iterable<QuickApp>): List<QuickApp> =
        apps.sortedWith(compareBy<QuickApp> { it.sortOrder }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label })

    fun enabled(apps: Iterable<QuickApp>): List<QuickApp> =
        sort(apps).filter { it.enabled }

    fun installedApps(records: Iterable<QuickApp>, installedApps: Iterable<InstalledApp>): List<InstalledApp> {
        val installedByPackage = installedApps.associateBy { it.packageName }
        return enabled(records).mapNotNull { installedByPackage[it.packageName] }
    }
}

/** SharedPreferences implementation; icon binaries are resolved from PackageManager at render time. */
class SharedPreferencesQuickAppsRepository(context: Context) : QuickAppsRepository {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun list(profileId: String): List<QuickApp> =
        QuickAppsCatalog.sort(
            packages().mapNotNull { packageName ->
                val storedProfile = prefs.getString(key(packageName, "profile"), DEFAULT_PROFILE) ?: DEFAULT_PROFILE
                if (storedProfile != profileId) return@mapNotNull null
                QuickApp(
                    packageName = packageName,
                    label = prefs.getString(key(packageName, "label"), packageName) ?: packageName,
                    iconKey = prefs.getString(key(packageName, "icon"), null),
                    enabled = prefs.getBoolean(key(packageName, "enabled"), true),
                    sortOrder = prefs.getInt(key(packageName, "order"), Int.MAX_VALUE),
                    profileId = storedProfile
                )
            }
        )

    override fun save(app: QuickApp) {
        val updated = packages().toMutableSet().apply { add(app.packageName) }
        prefs.edit {
            putStringSet(KEY_PACKAGES, updated)
            putString(key(app.packageName, "label"), app.label)
            putString(key(app.packageName, "icon"), app.iconKey)
            putBoolean(key(app.packageName, "enabled"), app.enabled)
            putInt(key(app.packageName, "order"), app.sortOrder)
            putString(key(app.packageName, "profile"), app.profileId)
        }
    }

    override fun setEnabled(packageName: String, enabled: Boolean, profileId: String) {
        if (packageName !in packages()) return
        val storedProfile = prefs.getString(key(packageName, "profile"), DEFAULT_PROFILE) ?: DEFAULT_PROFILE
        if (storedProfile == profileId) prefs.edit { putBoolean(key(packageName, "enabled"), enabled) }
    }

    override fun remove(packageName: String, profileId: String) {
        val storedProfile = prefs.getString(key(packageName, "profile"), DEFAULT_PROFILE) ?: DEFAULT_PROFILE
        if (storedProfile != profileId) return
        prefs.edit {
            putStringSet(KEY_PACKAGES, packages().toMutableSet().apply { remove(packageName) })
            remove(key(packageName, "label"))
            remove(key(packageName, "icon"))
            remove(key(packageName, "enabled"))
            remove(key(packageName, "order"))
            remove(key(packageName, "profile"))
        }
    }

    private fun packages(): Set<String> =
        prefs.getStringSet(KEY_PACKAGES, emptySet())?.toSet() ?: emptySet()

    private fun key(packageName: String, field: String): String = "app:$packageName:$field"

    private companion object {
        const val PREFS_NAME = "autobridge_quick_apps"
        const val KEY_PACKAGES = "packages"
        const val DEFAULT_PROFILE = "default"
    }
}

object QuickAppsStore {
    fun repository(context: Context): QuickAppsRepository = SharedPreferencesQuickAppsRepository(context)

    fun list(context: Context, profileId: String = "default"): List<QuickApp> =
        repository(context).list(profileId)

    fun enabledInstalledApps(
        context: Context,
        installedApps: List<InstalledApp>,
        profileId: String = "default"
    ): List<InstalledApp> = QuickAppsCatalog.installedApps(list(context, profileId), installedApps)

    fun isEnabled(context: Context, packageName: String, profileId: String = "default"): Boolean =
        list(context, profileId).any { it.packageName == packageName && it.enabled }

    /** Adds or updates one user-selected app without replacing its persisted profile settings. */
    fun setEnabled(
        context: Context,
        app: InstalledApp,
        enabled: Boolean,
        profileId: String = "default"
    ) {
        val repository = repository(context)
        val current = repository.list(profileId).firstOrNull { it.packageName == app.packageName }
        if (current == null) {
            if (enabled) {
                repository.save(
                    QuickApp(
                        packageName = app.packageName,
                        label = app.label,
                        sortOrder = repository.list(profileId).size,
                        profileId = profileId
                    )
                )
            }
        } else {
            repository.setEnabled(app.packageName, enabled, profileId)
        }
        // Keep the legacy favorite set compatible with older installed versions. It is no longer
        // read by the current UI; it only provides a one-time migration source.
        FavoriteAppsStore.setFavorite(context, app.packageName, enabled)
    }

    /** Migrates legacy favorites into Quick App records, preserving explicit enabled=false edits. */
    fun syncFavorites(context: Context, installedApps: List<InstalledApp>) {
        val repository = repository(context)
        val existing = repository.list().associateBy { it.packageName }
        val favorites = FavoriteAppsStore.favoritePackages(context)
        installedApps.filter { it.packageName in favorites }.forEachIndexed { index, installed ->
            val current = existing[installed.packageName]
            if (current == null) {
                repository.save(
                    QuickApp(
                        packageName = installed.packageName,
                        label = installed.label,
                        sortOrder = index,
                        profileId = "default"
                    )
                )
            } else if (current.label != installed.label) {
                repository.save(current.copy(label = installed.label))
            }
        }
    }
}
