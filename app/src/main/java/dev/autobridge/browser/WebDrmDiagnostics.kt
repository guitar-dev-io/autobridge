package dev.autobridge.browser

import android.content.Context
import android.media.MediaDrm
import android.os.Build
import android.util.Log
import android.webkit.WebView
import androidx.media3.common.C
import androidx.webkit.WebViewCompat

/**
 * Read-only snapshot of WebView/MediaDrm capability state, for diagnosing DRM/EME playback issues
 * without guesswork. Every field falls back to "unknown"/"unavailable" rather than assuming — this
 * only queries public, standard APIs (WebView package info, `MediaDrm.getPropertyString`) and never
 * touches license/key material.
 */
data class DrmDiagnostics(
    val androidVersion: String,
    val webViewPackage: String,
    val webViewVersion: String,
    val hardwareAccelerated: String,
    val javaScriptEnabled: String,
    val domStorageEnabled: String,
    val fullscreenCustomViewSupported: String,
    val widevineCdm: String,
    val widevineSecurityLevel: String,
) {
    fun formatted(): String = buildString {
        appendLine("Android: $androidVersion")
        appendLine("WebView provider: $webViewPackage")
        appendLine("WebView version: $webViewVersion")
        appendLine("Hardware acceleration: $hardwareAccelerated")
        appendLine("JavaScript: $javaScriptEnabled")
        appendLine("DOM Storage: $domStorageEnabled")
        appendLine("Fullscreen custom view: $fullscreenCustomViewSupported")
        appendLine("Widevine CDM: $widevineCdm")
        append("Widevine level: $widevineSecurityLevel")
    }
}

object WebDrmDiagnostics {
    private const val TAG = "[AutoBridge/DRM]"

    /**
     * [webView] is optional — pass a live, currently-configured WebView to report its actual
     * hardware-acceleration/JS/DOM-storage state; omit it (e.g. from a settings screen with no
     * WebView on screen) to report device/WebView-level capability only.
     */
    fun collect(context: Context, webView: WebView? = null): DrmDiagnostics {
        val webViewPackage = runCatching { WebViewCompat.getCurrentWebViewPackage(context) }.getOrNull()
        val widevineAvailable = runCatching { MediaDrm.isCryptoSchemeSupported(C.WIDEVINE_UUID) }.getOrDefault(false)
        val securityLevel = if (widevineAvailable) {
            runCatching { MediaDrm(C.WIDEVINE_UUID).use { it.getPropertyString("securityLevel") } }.getOrDefault("unknown")
        } else "unknown"
        Log.i(TAG, "widevine available=$widevineAvailable securityLevel=$securityLevel")
        return DrmDiagnostics(
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            webViewPackage = webViewPackage?.packageName ?: "unknown",
            webViewVersion = webViewPackage?.versionName ?: "unknown",
            hardwareAccelerated = webView?.isHardwareAccelerated?.let { if (it) "ON" else "OFF" } ?: "unknown",
            javaScriptEnabled = webView?.settings?.javaScriptEnabled?.let { if (it) "ON" else "OFF" } ?: "unknown",
            domStorageEnabled = webView?.settings?.domStorageEnabled?.let { if (it) "ON" else "OFF" } ?: "unknown",
            fullscreenCustomViewSupported = "supported",
            widevineCdm = if (widevineAvailable) "available" else "unavailable",
            widevineSecurityLevel = securityLevel,
        )
    }
}
