package dev.autobridge.update

import org.json.JSONObject

/**
 * One published AutoBridge release, as much of it as the update check needs.
 *
 * [versionName] is the normalized tag ([AppVersion.normalize]) so it can be compared with
 * `BuildConfig.VERSION_NAME` directly, and shown as the version the user would be moving to.
 */
data class UpdateRelease(
    val versionName: String,
    val tag: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    /** Direct link to the installable APK, when the release attached one. */
    val apkUrl: String?
)

/**
 * Reads the release JSON GitHub's `/releases/latest` endpoint returns.
 *
 * Kept apart from the HTTP call so the shape of the answer is unit-tested without the network. The
 * endpoint already excludes drafts and pre-releases, but [parseLatest] checks the flags anyway:
 * this response is a third party's, and nothing here should depend on an endpoint's filtering to
 * keep an unfinished build off someone's phone.
 */
object GitHubReleases {

    /** `owner/repo` the APKs are published under; see .github/workflows/release.yml. */
    const val REPOSITORY = "guitar-dev-io/autobridge"

    const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$REPOSITORY/releases/latest"

    /**
     * Returns the release, or null when the payload is not one this app can act on — no tag, a
     * draft, a pre-release, or a tag that is not a version ([AppVersion.isParsable]).
     *
     * [flavorHint] picks between several attached APKs (the workflow publishes the `safe` one, but
     * a release may carry more); it is only a preference, and any `.apk` is used otherwise.
     */
    fun parseLatest(json: String, flavorHint: String? = null): UpdateRelease? {
        val release = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) return null

        val tag = release.optString("tag_name").trim().ifEmpty { return null }
        if (!AppVersion.isParsable(tag)) return null

        val pageUrl = release.optString("html_url").trim()
            .ifEmpty { "https://github.com/$REPOSITORY/releases/tag/$tag" }

        return UpdateRelease(
            versionName = AppVersion.normalize(tag),
            tag = tag,
            // GitHub allows an empty release name, in which case the tag is what it is called.
            title = release.optString("name").trim().ifEmpty { tag },
            notes = release.optString("body").trim(),
            pageUrl = pageUrl,
            apkUrl = apkAsset(release, flavorHint)
        )
    }

    private fun apkAsset(release: JSONObject, flavorHint: String?): String? {
        val assets = release.optJSONArray("assets") ?: return null
        var fallback: String? = null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name").lowercase()
            if (!name.endsWith(".apk")) continue
            val url = asset.optString("browser_download_url").trim()
            if (url.isEmpty()) continue
            if (flavorHint != null && name.contains(flavorHint.lowercase())) return url
            if (fallback == null) fallback = url
        }
        return fallback
    }
}
