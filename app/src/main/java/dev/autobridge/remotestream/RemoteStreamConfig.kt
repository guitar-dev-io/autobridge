package dev.autobridge.remotestream

import android.content.Context
import androidx.core.content.edit
import java.net.URI
import java.util.Locale

/**
 * Where the stream host is, and whether the user has turned the feature on.
 *
 * Both halves matter to the router: [isAvailable] is what decides whether the remote-stream
 * branch exists at all for a given link, which is how "attempts RemoteStreamPlaybackEngine only
 * when a stream host is configured" is enforced in one place rather than at each call site.
 *
 * The feature ships off. It is experimental, it needs a host the user has to run themselves, and
 * a fallback that silently tried to reach a machine that was never set up would turn every
 * unsupported link into a timeout instead of an immediate, accurate message.
 */
object RemoteStreamConfig {
    private const val PREFS_NAME = "autobridge_remote_stream"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_ENABLED = "enabled"

    data class Config(val endpoint: String, val enabled: Boolean) {
        val isUsable: Boolean get() = enabled && normalizeEndpoint(endpoint) != null
    }

    fun current(context: Context): Config = Config(
        endpoint = prefs(context).getString(KEY_ENDPOINT, "").orEmpty(),
        enabled = prefs(context).getBoolean(KEY_ENABLED, false)
    )

    /** The one question the router asks. */
    fun isAvailable(context: Context): Boolean = current(context).isUsable

    /** The endpoint to dial, or null when what is stored is not usable. */
    fun endpoint(context: Context): String? {
        val config = current(context)
        if (!config.enabled) return null
        return normalizeEndpoint(config.endpoint)
    }

    fun setEndpoint(context: Context, raw: String) {
        prefs(context).edit { putString(KEY_ENDPOINT, raw.trim()) }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
    }

    /**
     * A `ws://`/`wss://` endpoint, or null.
     *
     * A bare `host:port` is accepted and assumed plaintext, because that is what a user reads off
     * the host's own console and the alternative is a setting that rejects the obvious input.
     * Pure, so the accepted forms are a unit test.
     */
    fun normalizeEndpoint(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val candidate = if (trimmed.contains("://")) trimmed else "ws://$trimmed"
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "ws" && scheme != "wss") return null
        if (uri.host.isNullOrBlank()) return null
        return candidate
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
