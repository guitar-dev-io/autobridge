package dev.autobridge.iptv

import android.content.Context
import androidx.core.content.edit

/**
 * Starred categories on the Library TV/Radio page, kept per source so a pin in one provider's
 * catalog never shows up in another's.
 *
 * Category ids and names are not credentials, unlike [IptvSourceStore] and [IptvHistoryStore], so
 * this is ordinary [android.content.SharedPreferences] rather than [dev.autobridge.core.crypto.SecretPrefs].
 */
object IptvPinnedCategoryStore {
    private const val PREFS_NAME = "autobridge_iptv_pinned_categories"

    fun pinned(context: Context, sourceId: String): Set<String> =
        prefs(context).getStringSet(sourceId, emptySet()).orEmpty()

    fun isPinned(context: Context, sourceId: String, categoryId: String): Boolean =
        categoryId in pinned(context, sourceId)

    /** Adds or removes [categoryId] from [sourceId]'s pins. Returns true when it is pinned afterwards. */
    fun toggle(context: Context, sourceId: String, categoryId: String): Boolean {
        val current = pinned(context, sourceId).toMutableSet()
        val nowPinned = if (current.remove(categoryId)) {
            false
        } else {
            current.add(categoryId)
            true
        }
        prefs(context).edit { putStringSet(sourceId, current) }
        return nowPinned
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
