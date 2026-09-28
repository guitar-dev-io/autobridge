package dev.autobridge.browser

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import androidx.core.content.edit
import dev.autobridge.entertainment.ContentAddress
import org.json.JSONArray
import org.json.JSONObject

/** A file the browser handed to the system download manager. */
data class BrowserDownload(
    val id: Long,
    val fileName: String,
    val url: String,
    val timestampMs: Long,
)

/**
 * Wires a WebView's download events to Android's own [DownloadManager].
 *
 * Downloads land in the app's external files directory, so no storage permission is requested and
 * nothing is written outside the app's own scope — the narrowest arrangement that still produces a
 * real, user-retrievable file. Only HTTPS is accepted; `blob:`/`data:`/`content:` downloads are
 * declined rather than handled through a bridge, because reaching them would mean injecting
 * JavaScript to exfiltrate page data, which is not a capability this browser takes on.
 */
object BrowserDownloads {
    private const val TAG = "[AutoBridge/Download]"
    private const val PREFS_NAME = "autobridge_browser_downloads"
    private const val KEY_ITEMS = "items"
    private const val MAX_ENTRIES = 30

    /**
     * Returns a listener for [android.webkit.WebView.setDownloadListener]. [onResult] reports a
     * short, user-facing outcome so each presentation can surface it in its own idiom (CarToast on
     * the car surface, Toast on the phone).
     */
    fun listener(context: Context, onResult: (String) -> Unit): DownloadListener =
        DownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            val safe = ContentAddress.https(url)
            if (safe == null) {
                Log.i(TAG, "declined non-https download scheme=${runCatching { Uri.parse(url).scheme }.getOrNull()}")
                onResult("รองรับเฉพาะดาวน์โหลดผ่าน HTTPS")
                return@DownloadListener
            }
            val fileName = URLUtil.guessFileName(safe, contentDisposition, mimeType)
            val queued = runCatching {
                val request = DownloadManager.Request(Uri.parse(safe)).apply {
                    setTitle(fileName)
                    setDescription(runCatching { Uri.parse(safe).host }.getOrNull().orEmpty())
                    setMimeType(mimeType)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalFilesDir(
                        context, Environment.DIRECTORY_DOWNLOADS, fileName
                    )
                    // Carry the page's session so an authenticated link does not download an error page.
                    runCatching { CookieManager.getInstance().getCookie(safe) }
                        .getOrNull()?.takeIf { it.isNotBlank() }
                        ?.let { addRequestHeader("Cookie", it) }
                    userAgent?.takeIf { it.isNotBlank() }?.let { addRequestHeader("User-Agent", it) }
                }
                val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                manager.enqueue(request)
            }.getOrElse { error ->
                Log.w(TAG, "enqueue failed", error)
                onResult("เริ่มดาวน์โหลดไม่สำเร็จ")
                return@DownloadListener
            }
            Log.i(TAG, "queued id=$queued name=$fileName bytes=$contentLength")
            record(context, BrowserDownload(queued, fileName, safe, System.currentTimeMillis()))
            onResult("กำลังดาวน์โหลด $fileName")
        }

    fun list(context: Context): List<BrowserDownload> {
        val raw = prefs(context).getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val url = obj.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                BrowserDownload(
                    obj.optLong("id"),
                    obj.optString("name", url),
                    url,
                    obj.optLong("ts", 0L)
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Live status text from the system download manager, or null when the entry is gone. */
    fun status(context: Context, id: Long): String? = runCatching {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) return@runCatching null
            when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> "เสร็จแล้ว"
                DownloadManager.STATUS_FAILED -> "ไม่สำเร็จ"
                DownloadManager.STATUS_PAUSED -> "หยุดชั่วคราว"
                DownloadManager.STATUS_PENDING -> "รอคิว"
                DownloadManager.STATUS_RUNNING -> "กำลังดาวน์โหลด"
                else -> null
            }
        }
    }.getOrNull()

    fun clear(context: Context) {
        prefs(context).edit { remove(KEY_ITEMS) }
    }

    private fun record(context: Context, entry: BrowserDownload) {
        val current = list(context).toMutableList()
        current.add(0, entry)
        while (current.size > MAX_ENTRIES) current.removeAt(current.size - 1)
        val array = JSONArray()
        current.forEach {
            array.put(
                JSONObject().apply {
                    put("id", it.id)
                    put("name", it.fileName)
                    put("url", it.url)
                    put("ts", it.timestampMs)
                }
            )
        }
        prefs(context).edit { putString(KEY_ITEMS, array.toString()) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
