package dev.autobridge.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a release's APK and hands it to an installer the user picks — KingInstaller, the
 * system package installer, anything that opens an APK.
 *
 * It only ever *offers* the file. Installing stays with that installer and the user's own tap in
 * it, which is where replacing this app belongs; this saves the browser download and the file
 * browsing in between. Picking KingInstaller is what keeps a sideloaded build visible on Android
 * Auto after the update, since it installs as the Play Store.
 */
object UpdateDownloader {
    private const val DIR = "updates"
    private const val FILE = "autobridge-update.apk"
    private const val MAX_BYTES = 200L * 1024 * 1024
    private const val TIMEOUT_MS = 20_000
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val PROGRESS_INTERVAL_MS = 150L

    /** Hosts a release APK is served from: the release link and the storage it redirects to. */
    private val ALLOWED_HOSTS = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    sealed interface Result {
        data class Ready(val apk: File) : Result
        data class Failed(val reason: String) : Result
        /** The user stopped it; nothing to report and no browser fallback. */
        object Cancelled : Result
    }

    /**
     * Downloads [url] into the app's cache and checks that it is an APK of this app before it is
     * offered to anything. Blocking: call off the main thread.
     *
     * [onProgress] gets the bytes so far and the total (-1 when the server does not say), on this
     * thread, at most every [PROGRESS_INTERVAL_MS]. [cancelled] is polled between reads.
     */
    fun download(
        context: Context,
        url: String,
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): Result {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val target = File(dir, FILE)
        target.delete()
        var connection: HttpURLConnection? = null
        try {
            var current = URL(url)
            // Redirects are followed by hand so every hop is checked against the allowed hosts.
            repeat(5) {
                if (current.protocol != "https" || current.host !in ALLOWED_HOSTS) {
                    return Result.Failed("unexpected download host ${current.host}")
                }
                connection?.disconnect()
                val opened = (current.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    setRequestProperty("User-Agent", "AutoBridge-Updater")
                }
                connection = opened
                val code = opened.responseCode
                if (code in 300..399) {
                    val location = opened.getHeaderField("Location") ?: return Result.Failed("redirect without a location")
                    current = URL(current, location)
                    return@repeat
                }
                if (code != HttpURLConnection.HTTP_OK) return Result.Failed("HTTP $code")
                val total = opened.contentLengthLong
                if (total > MAX_BYTES) return Result.Failed("file too large")
                var copied = 0L
                var reportedAt = 0L
                onProgress(0L, total)
                opened.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled()) {
                                target.delete()
                                return Result.Cancelled
                            }
                            val read = input.read(buffer)
                            if (read < 0) break
                            copied += read
                            if (copied > MAX_BYTES) return Result.Failed("file too large")
                            output.write(buffer, 0, read)
                            val now = System.currentTimeMillis()
                            if (now - reportedAt >= PROGRESS_INTERVAL_MS) {
                                reportedAt = now
                                onProgress(copied, total)
                            }
                        }
                    }
                }
                onProgress(copied, total)
                return verify(context, target)
            }
            return Result.Failed("too many redirects")
        } catch (error: Exception) {
            target.delete()
            return Result.Failed(error.message ?: error.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    /** Only an APK of this very app is offered; anything else is deleted. */
    private fun verify(context: Context, apk: File): Result {
        val info = runCatching { context.packageManager.getPackageArchiveInfo(apk.path, 0) }.getOrNull()
        if (info?.packageName != context.packageName) {
            apk.delete()
            return Result.Failed("the download is not an AutoBridge APK")
        }
        return Result.Ready(apk)
    }

    /**
     * An "Open with…" for [apk], so the user chooses the installer (KingInstaller, or the
     * system's). Read access to the file is granted to whichever app is chosen, and to nothing else.
     */
    fun openWithIntent(context: Context, apk: File, title: CharSequence): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, authority(context), apk)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Some installers take an APK only through Share, not Open. Those are added to the same
        // list as explicit targets, so the user sees one list of everything that installs.
        val viewers = context.packageManager.queryIntentActivities(view, 0)
            .map { it.activityInfo.packageName }.toSet()
        val sharers = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_SEND).setType(APK_MIME), 0
        )
            .filter { it.activityInfo.packageName !in viewers && it.activityInfo.packageName != context.packageName }
            .map { info ->
                Intent(Intent.ACTION_SEND)
                    .setType(APK_MIME)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .setClassName(info.activityInfo.packageName, info.activityInfo.name)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        return Intent.createChooser(view, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { if (sharers.isNotEmpty()) putExtra(Intent.EXTRA_INITIAL_INTENTS, sharers.toTypedArray()) }
    }

    fun authority(context: Context): String = context.packageName + ".updates"
}
