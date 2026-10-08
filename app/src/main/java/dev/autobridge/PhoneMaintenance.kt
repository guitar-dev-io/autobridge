package dev.autobridge

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.text.format.Formatter
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import dev.autobridge.diagnostics.LogReport
import dev.autobridge.install.InstallerSource
import dev.autobridge.update.UpdateDownloader
import dev.autobridge.update.WhatsNew
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The phone app's housekeeping flows, kept out of [MainActivity] (already the largest file in the
 * app): installing an update, what changed after one, whether Android Auto can still see Bridge
 * Web, and sending a log.
 *
 * Each flow only needs an Activity to show dialogs and start the next screen from, plus the two
 * MainActivity actions it falls back on, which are passed in rather than reached for.
 */
class PhoneMaintenance(
    private val activity: Activity,
    /** Opens an address in the browser; the fallback when a download cannot be handed over. */
    private val openUrl: (String) -> Unit,
    /** Shares the plain-text diagnostics; the fallback when the log file cannot be built. */
    private val fallbackShare: () -> Unit,
) {
    private companion object {
        const val POST_UPDATE_PREFS = "autobridge_post_update"
        const val KEY_LAST_SEEN_VERSION = "last_seen_version"
        const val KEY_AA_WARNED_VERSION = "aa_warned_version_code"
        const val PROJECTION_SETUP = "dev.autobridge.projection.ProjectionSetupActivity"
    }

    /** Once per process: the checks below are about this install, not about each return here. */
    private var postUpdateChecked = false

    private val gone: Boolean get() = activity.isFinishing || activity.isDestroyed

    /**
     * After an update: what changed, then whether Android Auto can still see Bridge Web.
     *
     * Installing a new APK by most means resets the recorded installer, and Android Auto then hides
     * the projection route until it is set back — which used to surface only as "Bridge Web is
     * gone from the car". The warning is shown once per version, with the fix one tap away.
     */
    fun runPostUpdateChecks() {
        if (postUpdateChecked) return
        postUpdateChecked = true
        val prefs = activity.getSharedPreferences(POST_UPDATE_PREFS, Context.MODE_PRIVATE)
        val current = BuildConfig.VERSION_NAME
        val notes = WhatsNew.since(prefs.getString(KEY_LAST_SEEN_VERSION, null), current)
        prefs.edit().putString(KEY_LAST_SEEN_VERSION, current).apply()
        val afterNotes = { checkAndroidAutoVisibility(prefs) }
        if (notes.isEmpty()) {
            afterNotes()
            return
        }
        val message = notes.joinToString("\n\n") { release ->
            "v${release.versionName}\n" + activity.getString(release.notes)
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.whats_new_title, current))
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { afterNotes() }
            .show()
    }

    private fun checkAndroidAutoVisibility(prefs: SharedPreferences) {
        if (gone) return
        val setup = Intent().setClassName(activity, PROJECTION_SETUP)
        if (activity.packageManager.resolveActivity(setup, 0) == null) return
        if (InstallerSource.isTrusted(installer())) return
        if (prefs.getInt(KEY_AA_WARNED_VERSION, -1) == BuildConfig.VERSION_CODE) return
        prefs.edit().putInt(KEY_AA_WARNED_VERSION, BuildConfig.VERSION_CODE).apply()
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.aa_hidden_title))
            .setMessage(activity.getString(R.string.aa_hidden_message))
            .setPositiveButton(activity.getString(R.string.aa_hidden_fix)) { _, _ -> activity.startActivity(setup) }
            .setNegativeButton(activity.getString(R.string.about_update_later), null)
            .show()
    }

    private fun installer(): String? = runCatching {
        val pm = activity.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(activity.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(activity.packageName)
        }
    }.getOrNull()

    /**
     * Downloads the release APK and opens "Install with…" so the user picks the installer —
     * KingInstaller keeps a sideloaded build visible on Android Auto. Installing is that app's job
     * and the user's tap there; see [UpdateDownloader]. Falls back to the browser download it
     * replaced when the in-app download fails.
     */
    fun downloadAndOfferInstall(apkUrl: String) {
        if (downloading) return
        downloading = true
        val appContext = activity.applicationContext
        val progress = DownloadProgress(activity)
        val cancelled = AtomicBoolean(false)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.about_update_downloading))
            .setView(progress.view)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelled.set(true) }
            .setCancelable(false)
            .show()
        Thread {
            val result = UpdateDownloader.download(
                appContext, apkUrl,
                onProgress = { copied, total -> activity.runOnUiThread { progress.show(copied, total) } },
                cancelled = { cancelled.get() || gone },
            )
            activity.runOnUiThread {
                downloading = false
                runCatching { dialog.dismiss() }
                if (gone) return@runOnUiThread
                when (result) {
                    is UpdateDownloader.Result.Ready -> {
                        val chooser = UpdateDownloader.openWithIntent(
                            activity, result.apk, activity.getString(R.string.about_update_install_with)
                        )
                        val opened = runCatching { activity.startActivity(chooser); true }.getOrDefault(false)
                        if (!opened) openUrl(apkUrl)
                    }
                    is UpdateDownloader.Result.Failed -> {
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.about_update_download_failed, result.reason),
                            Toast.LENGTH_LONG
                        ).show()
                        openUrl(apkUrl)
                    }
                    UpdateDownloader.Result.Cancelled -> Unit
                }
            }
        }.start()
    }

    /** One download at a time: a second tap on Update while one runs does nothing. */
    private var downloading = false

    /** The download dialog's body: a bar and "42% · 8.6 MB of 20.4 MB". */
    private class DownloadProgress(private val context: Context) {
        private val density = context.resources.displayMetrics.density
        private val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 1000
        }
        private val caption = TextView(context).apply {
            textSize = 14f
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        val view: View = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val side = (24 * density).toInt()
            setPadding(side, (12 * density).toInt(), side, 0)
            addView(bar)
            addView(caption)
        }

        fun show(copied: Long, total: Long) {
            val done = Formatter.formatShortFileSize(context, copied)
            if (total > 0) {
                bar.isIndeterminate = false
                bar.progress = (copied * 1000 / total).toInt().coerceIn(0, 1000)
                val percent = (copied * 100 / total).toInt().coerceIn(0, 100)
                caption.text = context.getString(
                    R.string.about_update_download_progress,
                    percent, done, Formatter.formatShortFileSize(context, total),
                )
            } else {
                caption.text = done
            }
        }
    }

    /**
     * Settings > Send log: builds [LogReport] (it reads logcat, so off the main thread) and opens
     * the share sheet with it attached. Falls back to the plain-text share if the file cannot be
     * built.
     */
    fun sendLogReport() {
        Toast.makeText(activity, activity.getString(R.string.send_log_preparing), Toast.LENGTH_SHORT).show()
        val appContext = activity.applicationContext
        Thread {
            val file = runCatching { LogReport.build(appContext) }.getOrNull()
            activity.runOnUiThread {
                if (gone) return@runOnUiThread
                if (file == null) {
                    fallbackShare()
                    return@runOnUiThread
                }
                val chooser = LogReport.shareIntent(activity, file, activity.getString(R.string.send_log_via))
                runCatching { activity.startActivity(chooser) }.onFailure { fallbackShare() }
            }
        }.start()
    }
}
