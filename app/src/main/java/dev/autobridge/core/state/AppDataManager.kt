package dev.autobridge.core.state

import android.content.Context
import dev.autobridge.diagnostics.CrashReportStore
import dev.autobridge.logging.StructuredLog
import dev.autobridge.iptv.IptvCatalog
import dev.autobridge.ui.ImageLoader
import java.io.File

/**
 * Clears AutoBridge's own cached files and persisted data.
 *
 * The app spreads state across three kinds of storage, and a "clear" action means something
 * different for each, so they are kept as separate levels rather than one destructive button:
 *
 *  - **Cache** — regenerable scratch data: the [ImageLoader] memory/disk logo cache and the
 *    in-memory [IptvCatalog]. Clearing it only costs a re-fetch; no user choice is lost. This is
 *    the safe, always-offerable action.
 *  - **History** — things the user accumulated but did not deliberately configure: recent lists,
 *    web history, command history, crash reports. Clearing it is a privacy action and is
 *    reversible only in the sense that it rebuilds as the app is used again.
 *  - **All data** — every `autobridge_*` SharedPreferences file plus the above: favorites,
 *    bookmarks, IPTV sources, per-app profiles, every setting. This returns the app to a
 *    first-launch state and is NOT reversible. Callers must confirm with the user first.
 *
 * Everything here operates on this app's own private storage only. It does not touch other apps,
 * MediaStore, or the system; "clear data" here is an in-app reset, not the system Settings action.
 */
object AppDataManager {
    private const val TAG = "AppData"

    /** Prefix every AutoBridge SharedPreferences file shares, bar a couple of legacy names. */
    private const val PREFIX = "autobridge_"

    /**
     * SharedPreferences files that do not carry the [PREFIX] but still belong to the app. Kept
     * explicit so a full wipe does not silently miss them, and so an unrelated third-party prefs
     * file could never be caught by a broad match.
     */
    private val EXTRA_PREFS = listOf(
        "entertainment",
        "car_browser_render"
    )

    data class ClearResult(
        val imageFilesDeleted: Int,
        val prefsFilesCleared: Int,
        val otherFilesDeleted: Int
    ) {
        val isEmpty: Boolean
            get() = imageFilesDeleted == 0 && prefsFilesCleared == 0 && otherFilesDeleted == 0
    }

    /**
     * Clears only regenerable caches. Settings, history, and favorites are untouched. Safe to offer
     * without a confirmation prompt.
     */
    fun clearCache(context: Context): ClearResult {
        val app = context.applicationContext
        val images = ImageLoader.clearCache(app)
        IptvCatalog.clearAll()
        val other = clearCacheDir(app)
        val result = ClearResult(imageFilesDeleted = images, prefsFilesCleared = 0, otherFilesDeleted = other)
        StructuredLog.i(TAG, "cleared cache: images=$images otherCacheFiles=$other")
        return result
    }

    /**
     * Wipes every AutoBridge SharedPreferences file, the caches, and the crash-report directory,
     * returning the app to a first-launch state.
     *
     * Irreversible: this removes favorites, bookmarks, IPTV sources, per-app profiles, and all
     * settings. The caller is responsible for confirming with the user before calling it.
     *
     * Runtime singletons that hold persisted values in `@Volatile var`s (e.g. MirrorSettings via
     * [dev.autobridge.settings.SettingsStore]) keep their current in-memory values until the next
     * process start or an explicit `restore()`; the durable backing is what is cleared here.
     */
    fun clearAllData(context: Context): ClearResult {
        val app = context.applicationContext
        val images = ImageLoader.clearCache(app)
        IptvCatalog.clearAll()
        val cacheFiles = clearCacheDir(app)
        val prefsCleared = clearAllPreferences(app)
        // CrashReportStore owns the diagnostics directory and guards it with its own lock, so clear
        // it through that API rather than walking the directory here.
        CrashReportStore.clear(app)
        StructuredLog.clear()
        val result = ClearResult(
            imageFilesDeleted = images,
            prefsFilesCleared = prefsCleared,
            otherFilesDeleted = cacheFiles
        )
        StructuredLog.w(
            TAG,
            "cleared ALL app data: prefsFiles=$prefsCleared images=$images otherCacheFiles=$cacheFiles"
        )
        return result
    }

    /**
     * Lists this app's SharedPreferences file names (without the `.xml` suffix). Derived from the
     * on-disk `shared_prefs` directory so it stays correct as stores are added, rather than from a
     * hand-maintained list.
     */
    fun preferenceFileNames(context: Context): List<String> {
        val dir = File(context.applicationContext.dataDir, "shared_prefs")
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.name.endsWith(".xml") }
            .map { it.name.removeSuffix(".xml") }
            .filter { it.startsWith(PREFIX) || it in EXTRA_PREFS }
            .sorted()
    }

    private fun clearAllPreferences(context: Context): Int {
        var cleared = 0
        preferenceFileNames(context).forEach { name ->
            runCatching {
                context.getSharedPreferences(name, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit()
            }.onSuccess { if (it) cleared++ }
                .onFailure { StructuredLog.w(TAG, "failed clearing prefs '$name': ${it.message}") }
        }
        return cleared
    }

    /** Deletes the contents of the app cache directory, leaving the directory itself in place. */
    private fun clearCacheDir(context: Context): Int = clearDirContents(context.cacheDir)

    /** Recursively deletes everything inside [dir], returning how many files were removed. */
    private fun clearDirContents(dir: File?): Int {
        if (dir == null || !dir.isDirectory) return 0
        var deleted = 0
        dir.listFiles()?.forEach { child ->
            deleted += if (child.isDirectory) {
                val inner = clearDirContents(child)
                child.delete()
                inner
            } else {
                if (child.delete()) 1 else 0
            }
        }
        return deleted
    }
}
