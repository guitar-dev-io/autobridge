package dev.autobridge.browser

import android.content.Context
import androidx.core.content.edit

enum class BrowserUserAgentMode {
    MOBILE,
    DESKTOP,
    CUSTOM
}

/** Persistent User-Agent selection shared by the car and phone-hosted browsers. */
object BrowserUserAgentStore {
    private const val PREFS_NAME = "autobridge_browser"
    private const val KEY_MODE = "user_agent_mode"
    private const val KEY_CUSTOM = "custom_user_agent"

    fun mode(context: Context): BrowserUserAgentMode =
        runCatching {
            BrowserUserAgentMode.valueOf(
                prefs(context).getString(KEY_MODE, BrowserUserAgentMode.MOBILE.name).orEmpty()
            )
        }.getOrDefault(BrowserUserAgentMode.MOBILE)

    fun custom(context: Context): String = prefs(context).getString(KEY_CUSTOM, "").orEmpty()

    fun select(context: Context, mode: BrowserUserAgentMode) {
        prefs(context).edit { putString(KEY_MODE, mode.name) }
    }

    fun saveCustom(context: Context, value: String): Boolean {
        val normalized = BrowserUserAgentCodec.normalizeCustom(value) ?: return false
        prefs(context).edit {
            putString(KEY_CUSTOM, normalized)
            putString(KEY_MODE, BrowserUserAgentMode.CUSTOM.name)
        }
        return true
    }

    fun resolve(context: Context, mobileDefault: String): String = when (mode(context)) {
        BrowserUserAgentMode.MOBILE -> BrowserUserAgentCodec.mobile(mobileDefault)
        BrowserUserAgentMode.DESKTOP -> BrowserUserAgentCodec.desktop(mobileDefault)
        BrowserUserAgentMode.CUSTOM -> custom(context).ifBlank { BrowserUserAgentCodec.mobile(mobileDefault) }
    }

    fun label(context: Context): String = when (mode(context)) {
        BrowserUserAgentMode.MOBILE -> "Mobile"
        BrowserUserAgentMode.DESKTOP -> "Desktop"
        BrowserUserAgentMode.CUSTOM -> "Custom"
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

object BrowserUserAgentCodec {
    private const val MAX_LENGTH = 512

    /**
     * Android's WebView marks its default user-agent as embedded in several places, and
     * accounts.google.com rejects a sign-in ("This browser or app may not be secure") if *any*
     * of them is present:
     *
     *  - a `; wv` token inside the platform comment,
     *  - a `Version/4.0` token before `Chrome/…`, which real Chrome for Android never sends, and
     *  - a `; <model> Build/<id>` fragment, which the desktop-class Chrome UA does not carry.
     *
     * Stripping tokens one at a time (the previous approach) is fragile: the exact device/Build
     * fragment varies per OEM, so a `replace` chain kept leaving pieces behind that still read as
     * a WebView. Instead this *rebuilds* the mobile UA from the two facts that must stay honest —
     * the WebKit build and the Chrome version the installed WebView actually reports — and pins
     * the rest to the shape real Chrome for Android sends. This matches how Fermata Auto composes
     * its WebView UA (AndreyPavlenko/Fermata, FermataWebView.UserAgent). If the platform UA does
     * not match the expected pattern, it falls back to the old token-stripping so the result is
     * never worse than before.
     */
    fun mobile(defaultUserAgent: String): String {
        val match = WEBKIT_AND_CHROME.find(defaultUserAgent)
        if (match != null) {
            val webkit = match.groupValues[1]
            val chrome = match.groupValues[2]
            val android = ANDROID_VERSION.find(defaultUserAgent)?.groupValues?.getOrNull(1)
                ?: FALLBACK_ANDROID_VERSION
            return "Mozilla/5.0 (Linux; Android $android) " +
                "AppleWebKit/$webkit (KHTML, like Gecko) " +
                "Chrome/$chrome Mobile Safari/$webkit"
        }
        // Pattern miss: keep the legacy strip so the UA is at least as clean as before.
        return defaultUserAgent
            .replace("; wv)", ")")
            .replace(" wv)", ")")
            .replace(VERSION_TOKEN, "")
            .replace(REPEATED_SPACE, " ")
            .trim()
    }

    /**
     * Desktop identity, built around the Chrome version the *installed* WebView actually reports
     * rather than a number pinned in source. A pinned version silently rots every time the system
     * WebView updates, and a user-agent claiming a years-old Chrome is itself enough for Google to
     * refuse a sign-in as an out-of-date browser.
     */
    fun desktop(defaultUserAgent: String): String {
        val version = CHROME_VERSION.find(defaultUserAgent)?.groupValues?.getOrNull(1)
            ?: FALLBACK_CHROME_VERSION
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$version Safari/537.36"
    }

    fun normalizeCustom(value: String?): String? {
        val normalized = value?.trim().orEmpty()
        if (normalized.isEmpty() || normalized.length > MAX_LENGTH) return null
        if (normalized.any { it == '\n' || it == '\r' || it.code < 0x20 }) return null
        return normalized
    }

    /**
     * Only used when the platform user-agent carries no `Chrome/` token at all — a rare fallback,
     * since a rebuilt UA normally reuses the version the installed WebView reports. Kept current
     * because a UA claiming a years-old Chrome is itself enough for Google to refuse a sign-in as
     * an out-of-date browser; bump this when the shipped Chrome/WebView line moves on.
     */
    private const val FALLBACK_CHROME_VERSION = "154.0.0.0"

    /** Only used when the platform user-agent carries no `Android <n>` token at all. */
    private const val FALLBACK_ANDROID_VERSION = "10"

    private val VERSION_TOKEN = Regex("""Version/\d+(?:\.\d+)*\s*""")
    private val CHROME_VERSION = Regex("""Chrome/(\d+(?:\.\d+)*)""")
    private val REPEATED_SPACE = Regex("""\s{2,}""")

    /** Captures the WebKit build (group 1) and the Chrome version (group 2) from a WebView UA. */
    private val WEBKIT_AND_CHROME =
        Regex("""AppleWebKit/(\S+) \(KHTML, like Gecko\).*?Chrome/(\d+(?:\.\d+)*)""")

    /** Captures the numeric Android release (e.g. "14") from the platform comment. */
    private val ANDROID_VERSION = Regex("""Android (\d+(?:\.\d+)*)""")
}
