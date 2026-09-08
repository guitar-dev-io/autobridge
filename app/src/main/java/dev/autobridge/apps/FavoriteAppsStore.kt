package dev.autobridge.apps

import android.content.Context
import androidx.core.content.edit

/** Persists which package names the driver has favorited for quick launch. */
object FavoriteAppsStore {
    private const val PREFS_NAME = "autobridge_favorites"
    private const val KEY_PACKAGES = "favorite_packages"

    fun isFavorite(context: Context, packageName: String): Boolean =
        favoritePackages(context).contains(packageName)

    fun setFavorite(context: Context, packageName: String, isFavorite: Boolean) {
        val prefs = prefs(context)
        // Never mutate the Set returned by getStringSet directly; copy first (documented Android gotcha).
        val current = prefs.getStringSet(KEY_PACKAGES, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (isFavorite) current.add(packageName) else current.remove(packageName)
        prefs.edit { putStringSet(KEY_PACKAGES, current) }
    }

    fun toggleFavorite(context: Context, packageName: String): Boolean {
        val newState = !isFavorite(context, packageName)
        setFavorite(context, packageName, newState)
        return newState
    }

    fun favoritePackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PACKAGES, emptySet())?.toSet() ?: emptySet()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/** Pure cross-reference, kept separate from [FavoriteAppsStore]'s SharedPreferences glue so it's unit-testable. */
object FavoriteAppsListing {
    /** Favorited apps that are still actually installed, in [installedApps]'s order (drops stale/uninstalled favorites). */
    fun resolve(installedApps: List<InstalledApp>, favoritePackages: Set<String>): List<InstalledApp> =
        installedApps.filter { it.packageName in favoritePackages }
}
