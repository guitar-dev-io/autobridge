package dev.autobridge.desktop

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * "Start on launch". Android creates content providers when the app process starts — whether the
 * user opened AutoBridge or Android Auto connected — so this is the earliest place to warm up the
 * desktop browser. It does nothing unless the setting is on. Exposes no data (not exported).
 */
class DesktopEngineStarter : ContentProvider() {
    override fun onCreate(): Boolean {
        val context = context ?: return true
        // A few seconds in, so the app's own start is not slowed down by a browser launch.
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                DesktopEngine.init(context)
                Thread({ DesktopEngine.prestartIfEnabled() }, "desktop-prestart").start()
            }.onFailure { DesktopLog.w("start on launch", it) }
        }, START_DELAY_MS)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val START_DELAY_MS = 6_000L
    }
}
