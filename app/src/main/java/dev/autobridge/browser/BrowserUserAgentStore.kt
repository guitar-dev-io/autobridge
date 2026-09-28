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

    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    /**
     * Android's WebView appends a "; wv" token to its default user-agent, which is exactly the
     * signal Google's accounts.google.com sign-in flow checks for to reject embedded WebView
     * logins (its documented anti-phishing policy). Stripping just that token — nothing else —
     * makes the request look like an ordinary Chrome page load, letting sign-in complete inside
     * this WebView instead of bouncing to an external browser task. This does not spoof anything
     * else about the browser (rendering engine, version, platform all stay accurate); it only
     * removes the one marker that exists purely to identify "this is a wrapped WebView".
     */
    private fun stripWebViewToken(userAgent: String): String =
        userAgent.replace("; wv)", ")").replace(" wv)", ")")

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
        BrowserUserAgentMode.MOBILE -> stripWebViewToken(mobileDefault)
        BrowserUserAgentMode.DESKTOP -> DESKTOP_USER_AGENT
        BrowserUserAgentMode.CUSTOM -> custom(context).ifBlank { mobileDefault }
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

    fun normalizeCustom(value: String?): String? {
        val normalized = value?.trim().orEmpty()
        if (normalized.isEmpty() || normalized.length > MAX_LENGTH) return null
        if (normalized.any { it == '\n' || it == '\r' || it.code < 0x20 }) return null
        return normalized
    }
}
