package dev.autobridge.update

import androidx.annotation.StringRes
import dev.autobridge.R

/**
 * What changed in each release, shown once after updating so the driver knows what to try on the
 * car (and what to set up again) without reading GitHub.
 *
 * Newest first. A release with nothing worth telling the driver is simply not listed.
 */
object WhatsNew {
    data class Release(val versionName: String, @StringRes val notes: Int)

    val releases: List<Release> = listOf(
        Release("0.4.35", R.string.whats_new_0_4_35),
        Release("0.4.34", R.string.whats_new_0_4_34),
        Release("0.4.33", R.string.whats_new_0_4_33),
        Release("0.4.32", R.string.whats_new_0_4_32),
        Release("0.4.31", R.string.whats_new_0_4_31),
        Release("0.4.30", R.string.whats_new_0_4_30),
        Release("0.4.29", R.string.whats_new_0_4_29),
        Release("0.4.28", R.string.whats_new_0_4_28),
        Release("0.4.27", R.string.whats_new_0_4_27),
        Release("0.4.26", R.string.whats_new_0_4_26),
        Release("0.4.25", R.string.whats_new_0_4_25),
        Release("0.4.24", R.string.whats_new_0_4_24),
    )

    /**
     * The releases to show after moving from [lastSeen] to [current]: every listed release newer
     * than [lastSeen] and not newer than [current], newest first, at most [limit]. Nothing on a
     * fresh install ([lastSeen] null) — there is no "new" to someone who just arrived.
     */
    fun since(lastSeen: String?, current: String, limit: Int = 3): List<Release> {
        if (lastSeen == null || AppVersion.compare(lastSeen, current) >= 0) return emptyList()
        return releases
            .filter { AppVersion.compare(it.versionName, lastSeen) > 0 && AppVersion.compare(it.versionName, current) <= 0 }
            .take(limit)
    }
}
