package dev.autobridge.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two halves of the update check that can be wrong without the network noticing: which of two
 * version names is newer, and what a GitHub release payload actually says.
 */
class UpdateCheckTest {

    @Test
    fun `a published release above the running build is an update`() {
        assertTrue(AppVersion.isNewer("0.5.0", "0.4.12"))
        assertTrue(AppVersion.isNewer("v0.4.13", "0.4.12"))
        assertTrue(AppVersion.isNewer("1.0.0", "0.9.9"))
    }

    @Test
    fun `the same version, an older one, or a local build ahead of it is not`() {
        assertFalse(AppVersion.isNewer("0.4.12", "0.4.12"))
        assertFalse(AppVersion.isNewer("v0.4.12", "0.4.12"))
        assertFalse(AppVersion.isNewer("0.4.11", "0.4.12"))
        // Working on the next release locally: the published build is behind, not ahead.
        assertFalse(AppVersion.isNewer("0.4.12", "0.5.0"))
    }

    @Test
    fun `segments compare as numbers, not as text`() {
        // The bug this guards: "0.4.9" > "0.4.12" under string ordering, so every user on 0.4.12
        // would have been offered a downgrade.
        assertTrue(AppVersion.compare("0.4.12", "0.4.9") > 0)
        assertFalse(AppVersion.isNewer("0.4.9", "0.4.12"))
        assertTrue(AppVersion.compare("0.10.0", "0.9.0") > 0)
    }

    @Test
    fun `a missing segment is zero`() {
        assertEquals(0, AppVersion.compare("0.5", "0.5.0"))
        assertEquals(0, AppVersion.compare("1", "1.0.0"))
        assertTrue(AppVersion.compare("0.5.1", "0.5") > 0)
    }

    @Test
    fun `a release outranks its own candidates`() {
        assertTrue(AppVersion.compare("0.5.0", "0.5.0-rc1") > 0)
        assertTrue(AppVersion.compare("0.5.0-rc2", "0.5.0-rc1") > 0)
        assertTrue(AppVersion.isNewer("0.5.0", "0.5.0-rc1"))
        assertFalse(AppVersion.isNewer("0.5.0-rc1", "0.5.0"))
    }

    @Test
    fun `build metadata and whitespace do not change the version`() {
        assertEquals("0.5.0", AppVersion.normalize("  v0.5.0+ci.42 "))
        assertEquals(0, AppVersion.compare("0.5.0+abc", "v0.5.0"))
    }

    @Test
    fun `a tag that is not a version is never treated as an update`() {
        assertFalse(AppVersion.isParsable("nightly"))
        assertFalse(AppVersion.isParsable(""))
        assertFalse(AppVersion.isParsable("0.5.x"))
        assertFalse(AppVersion.isNewer("nightly", "0.4.12"))
        assertFalse(AppVersion.isNewer("0.5.0", "not-a-version"))
    }

    @Test
    fun `a release payload yields the version, the page and the apk`() {
        val release = GitHubReleases.parseLatest(RELEASE_JSON, flavorHint = "SAFE")
        requireNotNull(release)
        assertEquals("0.5.0", release.versionName)
        assertEquals("v0.5.0", release.tag)
        assertEquals("AutoBridge 0.5.0", release.title)
        assertTrue(release.notes.contains("split screen"))
        assertEquals("https://github.com/guitar-dev-io/autobridge/releases/tag/v0.5.0", release.pageUrl)
        // The flavor hint picks the safe APK over the other attachments, and never the .aab.
        assertEquals(
            "https://github.com/guitar-dev-io/autobridge/releases/download/v0.5.0/app-safe-release.apk",
            release.apkUrl
        )
    }

    @Test
    fun `any apk will do when nothing matches the flavor`() {
        val release = GitHubReleases.parseLatest(RELEASE_JSON, flavorHint = "LAB")
        requireNotNull(release)
        assertTrue(release.apkUrl!!.endsWith(".apk"))
    }

    @Test
    fun `a release with no apk still offers its page`() {
        val release = GitHubReleases.parseLatest(
            """{"tag_name":"v0.6.0","html_url":"https://example.invalid/r","assets":[]}"""
        )
        requireNotNull(release)
        assertNull(release.apkUrl)
        assertEquals("https://example.invalid/r", release.pageUrl)
    }

    @Test
    fun `an unfinished or unreadable release is not offered`() {
        // Drafts and pre-releases are rejected here as well as by the endpoint.
        assertNull(GitHubReleases.parseLatest("""{"tag_name":"v9.9.9","draft":true}"""))
        assertNull(GitHubReleases.parseLatest("""{"tag_name":"v9.9.9","prerelease":true}"""))
        assertNull(GitHubReleases.parseLatest("""{"tag_name":"nightly"}"""))
        assertNull(GitHubReleases.parseLatest("""{"html_url":"https://example.invalid"}"""))
        assertNull(GitHubReleases.parseLatest("not json at all"))
    }

    @Test
    fun `a release without a name is called by its tag`() {
        val release = GitHubReleases.parseLatest("""{"tag_name":"v0.7.0","name":""}""")
        requireNotNull(release)
        assertEquals("v0.7.0", release.title)
        assertEquals("https://github.com/guitar-dev-io/autobridge/releases/tag/v0.7.0", release.pageUrl)
    }

    private companion object {
        /** Trimmed to the fields the parser reads, in the shape /releases/latest returns them. */
        const val RELEASE_JSON = """
        {
          "tag_name": "v0.5.0",
          "name": "AutoBridge 0.5.0",
          "draft": false,
          "prerelease": false,
          "html_url": "https://github.com/guitar-dev-io/autobridge/releases/tag/v0.5.0",
          "body": "- Projection route: split screen beside the navigation app\n- Car Home dashboard",
          "assets": [
            {
              "name": "app-safe-release.aab",
              "browser_download_url": "https://github.com/guitar-dev-io/autobridge/releases/download/v0.5.0/app-safe-release.aab"
            },
            {
              "name": "app-personal-release.apk",
              "browser_download_url": "https://github.com/guitar-dev-io/autobridge/releases/download/v0.5.0/app-personal-release.apk"
            },
            {
              "name": "app-safe-release.apk",
              "browser_download_url": "https://github.com/guitar-dev-io/autobridge/releases/download/v0.5.0/app-safe-release.apk"
            }
          ]
        }
        """
    }
}
