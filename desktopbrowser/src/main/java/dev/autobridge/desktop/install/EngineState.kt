package dev.autobridge.desktop.install

import android.content.Context
import dev.autobridge.desktop.browser.DesktopBrowserId

/** Lifecycle of the shared runtime and of each browser. */
enum class InstallState {
    NOT_INSTALLED,
    DOWNLOADING,
    VERIFYING,
    EXTRACTING,
    CONFIGURING,
    READY,
    RUNNING,
    FAILED,
    UPDATE_AVAILABLE;

    val busy: Boolean get() = this == DOWNLOADING || this == VERIFYING || this == EXTRACTING || this == CONFIGURING
    val installed: Boolean get() = this == READY || this == RUNNING || this == UPDATE_AVAILABLE
}

/**
 * One component's state as the UI shows it. [progress] is 0..1, or null when the step has no
 * measurable progress; [bytesDone]/[bytesTotal] are the current download, when there is one.
 */
data class ComponentState(
    val state: InstallState,
    val progress: Float? = null,
    val detail: String? = null,
    val version: String? = null,
    val candidateVersion: String? = null,
    val lastError: String? = null,
    val bytesDone: Long = 0,
    val bytesTotal: Long = -1,
)

/** Which browser the Desktop Browser entries open. [AUTOBRIDGE] means the desktop engine is off. */
enum class EngineChoice {
    AUTOBRIDGE, CHROME, BRAVE;

    val browser: DesktopBrowserId? get() = when (this) {
        AUTOBRIDGE -> null
        CHROME -> DesktopBrowserId.CHROME
        BRAVE -> DesktopBrowserId.BRAVE
    }
}

/** What survives a restart: versions, last errors, the user's choice and consent. */
class EngineStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("desktop_engine", Context.MODE_PRIVATE)

    var choice: EngineChoice
        get() = runCatching { EngineChoice.valueOf(prefs.getString("choice", null) ?: "") }.getOrDefault(EngineChoice.AUTOBRIDGE)
        set(value) { prefs.edit().putString("choice", value.name).apply() }

    var runtimeVersion: String?
        get() = prefs.getString("runtime_version", null)
        set(value) { prefs.edit().putString("runtime_version", value).apply() }

    var runtimeError: String?
        get() = prefs.getString("runtime_error", null)
        set(value) { prefs.edit().putString("runtime_error", value).apply() }

    var glibcVersion: String?
        get() = prefs.getString("glibc_version", null)
        set(value) { prefs.edit().putString("glibc_version", value).apply() }

    var seccompAcceleration: Boolean
        get() = prefs.getBoolean("seccomp", true)
        set(value) { prefs.edit().putBoolean("seccomp", value).apply() }

    fun version(id: DesktopBrowserId): String? = prefs.getString("version_${id.key}", null)
    fun setVersion(id: DesktopBrowserId, value: String?) { prefs.edit().putString("version_${id.key}", value).apply() }

    fun candidate(id: DesktopBrowserId): String? = prefs.getString("candidate_${id.key}", null)
    fun setCandidate(id: DesktopBrowserId, value: String?) { prefs.edit().putString("candidate_${id.key}", value).apply() }

    fun error(id: DesktopBrowserId): String? = prefs.getString("error_${id.key}", null)
    fun setError(id: DesktopBrowserId, value: String?) { prefs.edit().putString("error_${id.key}", value).apply() }

    /** When the user accepted the Google Chrome Terms of Service; Chrome is not fetched before. */
    var chromeTermsAcceptedAt: Long
        get() = prefs.getLong("chrome_terms_at", 0)
        set(value) { prefs.edit().putLong("chrome_terms_at", value).apply() }
}
