package dev.autobridge.library

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import dev.autobridge.core.state.AppDataManager
import dev.autobridge.i18n.AppLocale
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.stack
import java.io.File

/**
 * Storage section of settings: clear regenerable caches, or reset the app to a first-launch state.
 *
 * The two actions are deliberately separated and treated differently. "Clear cache" only drops
 * logos and the in-memory catalog, so it runs on tap and reports what it freed. "Clear all data"
 * removes every setting, favourite, bookmark and IPTV source and cannot be undone, so it always
 * goes through a confirmation dialog first. All work is delegated to [AppDataManager]; this screen
 * only presents it and shows the result.
 */
class StorageSettingsActivity : Activity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, StorageSettingsActivity::class.java)
    }

    private val accent = AutoBridgeDesign.ACCENT_SYSTEM

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.rebase(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val body = AutoBridgeDesign.body(this)

        body.stack(AutoBridgeDesign.sectionLabel(this, "Cache"), gap = 2)
        body.stack(
            AutoBridgeDesign.contentRow(
                context = this,
                title = "Clear cache",
                subtitle = "Logos and catalog data • ${formatSize(cacheBytes())}",
                accent = accent,
                badgeText = "↻"
            ) { confirmClearCache() }
        )

        body.stack(AutoBridgeDesign.sectionLabel(this, "Reset"), gap = 2)
        body.stack(
            AutoBridgeDesign.contentRow(
                context = this,
                title = "Clear all data",
                subtitle = "Remove every setting, favourite and source",
                accent = AutoBridgeDesign.DANGER,
                badgeText = "⚠"
            ) { confirmClearAll() }
        )

        setContentView(
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(
                    context = this,
                    title = "Storage",
                    subtitle = "Cache and app data",
                    onBack = { finish() }
                ),
                body = body
            )
        )
    }

    /**
     * Cache clearing loses nothing the user chose, so the confirmation is light: it names what will
     * happen and runs. Kept as a dialog anyway so the freed size can be reported back in the toast.
     */
    private fun confirmClearCache() {
        val result = AppDataManager.clearCache(this)
        val message = if (result.isEmpty) {
            "Cache was already empty"
        } else {
            "Cleared ${result.imageFilesDeleted + result.otherFilesDeleted} cached file(s)"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        render()
    }

    /**
     * The irreversible one. The dialog states plainly that favourites, sources and settings go, and
     * the confirming button carries that word rather than a bare "OK".
     */
    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle("Clear all data?")
            .setMessage(
                "This removes every setting, favourite, bookmark, IPTV source and per-app " +
                    "profile, returning AutoBridge to a fresh install. This cannot be undone."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear everything") { _, _ ->
                val result = AppDataManager.clearAllData(this)
                Toast.makeText(
                    this,
                    "Cleared ${result.prefsFilesCleared} setting file(s) and all caches",
                    Toast.LENGTH_LONG
                ).show()
                render()
            }
            .show()
    }

    /** Rough on-disk size of the app cache directory, shown so the row says why it is worth tapping. */
    private fun cacheBytes(): Long = dirSize(cacheDir)

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { dirSize(it) } ?: 0L
    }

    private fun formatSize(bytes: Long): String = when {
        bytes <= 0L -> "empty"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
