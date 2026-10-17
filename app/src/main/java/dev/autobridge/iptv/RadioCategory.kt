package dev.autobridge.iptv

import androidx.annotation.StringRes
import dev.autobridge.R

/**
 * The top-level radio browse categories, in display order. Each resolves to a radio-browser query
 * (except [FAVORITES], which reads [IptvHistoryStore]). [labelRes] keeps every user-visible label in
 * resources so no Thai literal appears in Kotlin.
 *
 * [cacheKey] is the stable, Android-free string the fetcher keys its cache and stale-guard on. The
 * key logic is pure so it is unit-tested on the JVM.
 */
enum class RadioCategory(@StringRes val labelRes: Int) {
    IN_COUNTRY(R.string.radio_cat_in_country),
    LOCAL(R.string.radio_cat_local),
    WORLD(R.string.radio_cat_world),
    PODCASTS(R.string.radio_cat_podcasts),
    FAVORITES(R.string.radio_cat_favorites);

    /**
     * The cache/stale-guard key for this category. [state] is the chosen province for [LOCAL]
     * (ignored otherwise). [FAVORITES] has no network fetch and so no key — it returns null.
     */
    fun cacheKey(state: String? = null): String? = when (this) {
        IN_COUNTRY -> "in_country"
        WORLD -> "world"
        LOCAL -> "local:${state.orEmpty()}"
        PODCASTS -> "podcasts:" + PODCAST_TAGS.joinToString("+")
        FAVORITES -> null
    }

    companion object {
        /** The tags Podcasts fans out over, in merge order. Shared with [RadioBrowserBrowse]. */
        val PODCAST_TAGS: List<String> = listOf("talk", "news", "podcast")
    }
}
