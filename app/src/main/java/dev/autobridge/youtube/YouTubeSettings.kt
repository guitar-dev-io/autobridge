package dev.autobridge.youtube

import android.content.Context
import androidx.core.content.edit

/**
 * The YouTube add-on settings, in the app's own private preferences.
 *
 * Everything here is off unless the user turns it on. Skipping parts of a video and overriding the
 * quality a site chose are both changes to what someone asked to watch, and the network traffic
 * SponsorBlock adds goes to a third party, so none of it may happen by default.
 */
object YouTubeSettings {
    private const val PREFS_NAME = "autobridge_youtube"
    private const val KEY_SPONSOR_BLOCK = "sponsor_block_enabled"
    private const val KEY_HIGHEST_QUALITY = "auto_highest_quality"
    private const val CATEGORY_PREFIX = "sponsor_category_"

    fun sponsorBlockEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SPONSOR_BLOCK, false)

    fun setSponsorBlockEnabled(context: Context, value: Boolean) =
        prefs(context).edit { putBoolean(KEY_SPONSOR_BLOCK, value) }

    fun autoHighestQuality(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HIGHEST_QUALITY, false)

    fun setAutoHighestQuality(context: Context, value: Boolean) =
        prefs(context).edit { putBoolean(KEY_HIGHEST_QUALITY, value) }

    fun isCategoryEnabled(context: Context, category: SponsorCategory): Boolean =
        prefs(context).getBoolean(CATEGORY_PREFIX + category.apiId, category.onByDefault)

    fun setCategoryEnabled(context: Context, category: SponsorCategory, value: Boolean) =
        prefs(context).edit { putBoolean(CATEGORY_PREFIX + category.apiId, value) }

    /** The categories a lookup should ask for; empty when the feature is off. */
    fun enabledCategories(context: Context): Set<SponsorCategory> {
        if (!sponsorBlockEnabled(context)) return emptySet()
        return SponsorCategory.entries.filterTo(linkedSetOf()) { isCategoryEnabled(context, it) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
