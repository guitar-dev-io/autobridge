package dev.autobridge.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import dev.autobridge.BuildConfig
import dev.autobridge.logging.StructuredLog
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks GitHub whether a newer AutoBridge has been released.
 *
 * AutoBridge is sideloaded from the GitHub Releases the release workflow publishes, so nothing
 * tells the user a new build exists — this does. The check only runs when the user taps it on
 * Settings > About: it is a request to a third party, and a build that reaches out on its own
 * every launch is not something the person installing an APK by hand asked for. The request
 * carries no identity, only the User-Agent below, and the result is cached so the row can show
 * what it last learned without going back out.
 *
 * Installing is deliberately left to an installer the user picks. [UpdateDownloader] fetches the
 * APK and offers it with "Install with…"; this app never replaces itself, so the decision stays
 * where the user can see it.
 */
object UpdateChecker {
    private const val PREFS_NAME = "autobridge_update"
    private const val KEY_LAST_CHECK_AT = "last_check_at"
    private const val KEY_LAST_SEEN_VERSION = "last_seen_version"

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val MAX_BODY_BYTES = 512 * 1024

    /** What a finished check found. */
    sealed interface Result {
        /** The running build is the latest published one (or is ahead of it, on a local build). */
        data class UpToDate(val currentVersion: String, val latestVersion: String) : Result

        data class Available(val release: UpdateRelease, val currentVersion: String) : Result

        /** The check could not complete; [reason] is for the log and a short toast, not a dialog. */
        data class Failed(val reason: String) : Result
    }

    /** The outcome of the previous check, for the About row's caption. */
    data class LastCheck(
        val checkedAtEpochMillis: Long,
        val latestVersion: String,
        val updateAvailable: Boolean
    )

    /**
     * Runs the check on a background thread and delivers the result on the main thread.
     *
     * [onResult] is called exactly once. The caller is an Activity, so it must still confirm it is
     * alive before touching views — a check in flight outlives a rotation or a back press.
     */
    fun checkAsync(
        context: Context,
        currentVersion: String = BuildConfig.VERSION_NAME,
        onResult: (Result) -> Unit
    ) {
        val appContext = context.applicationContext
        val mainHandler = Handler(Looper.getMainLooper())
        Thread({
            val result = check(appContext, currentVersion)
            mainHandler.post { onResult(result) }
        }, "autobridge-update-check").start()
    }

    /** Blocking; call from a background thread. Never throws. */
    fun check(context: Context, currentVersion: String = BuildConfig.VERSION_NAME): Result {
        val body = runCatching { fetch(GitHubReleases.LATEST_RELEASE_API, currentVersion) }
            .onFailure { StructuredLog.w("UPDATE", "Update check failed: ${it.message}") }
            .getOrElse { return Result.Failed(it.message ?: it.javaClass.simpleName) }

        val release = GitHubReleases.parseLatest(body, flavorHint = BuildConfig.AUTOBRIDGE_MODE)
            ?: return Result.Failed("no published release to compare against")

        val available = AppVersion.isNewer(release.versionName, currentVersion)
        remember(context, release.versionName)
        StructuredLog.i(
            "UPDATE",
            "Latest release ${release.versionName}, running $currentVersion, " +
                "update ${if (available) "available" else "not needed"}"
        )
        return if (available) {
            Result.Available(release, currentVersion)
        } else {
            Result.UpToDate(currentVersion, release.versionName)
        }
    }

    /** Null until a check has completed once on this device. */
    fun lastCheck(context: Context): LastCheck? {
        val prefs = prefs(context)
        val checkedAt = prefs.getLong(KEY_LAST_CHECK_AT, 0L)
        val latest = prefs.getString(KEY_LAST_SEEN_VERSION, null)
        if (checkedAt <= 0L || latest.isNullOrEmpty()) return null
        // Recomputed rather than stored: the build can change under a cached result (a sideload
        // over the top), and then "update available" would be stale the next time it is drawn.
        return LastCheck(
            checkedAtEpochMillis = checkedAt,
            latestVersion = latest,
            updateAvailable = AppVersion.isNewer(latest, BuildConfig.VERSION_NAME)
        )
    }

    private fun remember(context: Context, latestVersion: String) {
        prefs(context).edit {
            putLong(KEY_LAST_CHECK_AT, System.currentTimeMillis())
            putString(KEY_LAST_SEEN_VERSION, latestVersion)
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun fetch(url: String, currentVersion: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            // GitHub's API rejects requests without one. The app name and its version are all it
            // says; nothing identifies the device or the user.
            setRequestProperty("User-Agent", "AutoBridge/$currentVersion")
        }
        try {
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                // The repository has no release yet, which is an answer rather than a fault.
                throw IllegalStateException("no releases published yet")
            }
            if (status == 403 || status == 429) {
                throw IllegalStateException("GitHub rate limit reached, try again later")
            }
            if (status !in 200..299) throw IllegalStateException("HTTP $status")
            return connection.inputStream.use { stream ->
                // Bounded read: a public endpoint is not trusted to send something this device
                // should hold in memory whole. The bytes are decoded once at the end rather than
                // per chunk, because release notes are prose and a multi-byte character can
                // straddle two reads.
                val buffer = ByteArray(8 * 1024)
                val body = java.io.ByteArrayOutputStream()
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    if (body.size() + read > MAX_BODY_BYTES) {
                        throw IllegalStateException("response too large")
                    }
                    body.write(buffer, 0, read)
                }
                body.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }
}
