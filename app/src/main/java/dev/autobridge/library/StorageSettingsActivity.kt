package dev.autobridge.library

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import dev.autobridge.R
import dev.autobridge.core.state.AppDataManager
import dev.autobridge.i18n.AppLocale
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.stack
import java.io.File

/**
 * Storage section of settings: clear regenerable caches, or reset the app to a first-launch state.
 *
 * Both actions confirm first, worded to match what they actually do: "Clear cache" only drops
 * logos and the in-memory catalog, so its dialog says that and nothing you set up is affected.
 * "Clear all data" removes every setting, favourite, bookmark and IPTV source and cannot be
 * undone, so its dialog says that plainly and the confirming button carries that word rather than
 * a bare "OK". All work is delegated to [AppDataManager]; this screen only presents it and shows
 * the result.
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

        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.storage_section_cache)), gap = 2)
        body.stack(
            AutoBridgeDesign.contentRow(
                context = this,
                title = getString(R.string.storage_clear_cache),
                subtitle = getString(R.string.storage_clear_cache_caption, formatSize(cacheBytes())),
                accent = accent,
                badgeText = "↻"
            ) { confirmClearCache() }
        )

        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.storage_section_reset)), gap = 2)
        body.stack(
            AutoBridgeDesign.contentRow(
                context = this,
                title = getString(R.string.storage_clear_all),
                subtitle = getString(R.string.storage_clear_all_caption),
                accent = AutoBridgeDesign.DANGER,
                badgeText = "⚠"
            ) { confirmClearAll() }
        )

        setContentView(
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(
                    context = this,
                    title = getString(R.string.storage_title),
                    subtitle = getString(R.string.storage_subtitle),
                    onBack = { finish() }
                ),
                body = body
            )
        )
    }

    /**
     * Cache clearing loses nothing the user chose, so the confirmation is light: it names what will
     * happen, then runs. The dialog also lets the freed size be reported back in the toast.
     */
    private fun confirmClearCache() {
        AlertDialog.Builder(this)
            .setTitle(R.string.storage_clear_cache_confirm_title)
            .setMessage(R.string.storage_clear_cache_confirm_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.storage_clear_cache_action) { _, _ ->
                val result = AppDataManager.clearCache(this)
                val message = if (result.isEmpty) {
                    getString(R.string.storage_cache_empty)
                } else {
                    getString(R.string.storage_cache_cleared, result.imageFilesDeleted + result.otherFilesDeleted)
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                render()
            }
            .show()
    }

    /**
     * The irreversible one. The dialog states plainly that favourites, sources and settings go, and
     * the confirming button carries that word rather than a bare "OK".
     */
    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle(R.string.storage_clear_all_confirm_title)
            .setMessage(R.string.storage_clear_all_confirm_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.storage_clear_all_action) { _, _ ->
                val result = AppDataManager.clearAllData(this)
                Toast.makeText(
                    this,
                    getString(R.string.storage_clear_all_done, result.prefsFilesCleared),
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
