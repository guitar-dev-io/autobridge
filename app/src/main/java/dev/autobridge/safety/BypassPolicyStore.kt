package dev.autobridge.safety

import android.content.Context
import androidx.core.content.edit
import dev.autobridge.display.StructuredLog
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Runtime-controllable override for the parked/mode feature gate.
 *
 * ## Why this exists
 *
 * [SafetyEnforcement] decides at *build* time whether the gate blocks; [FeaturePolicy] decides at
 * runtime whether a feature is allowed. Neither can be flipped by the driver without a rebuild.
 * This store adds a single, persisted switch so the gate can be opened or closed on the device —
 * from the phone Settings screen or an `adb` broadcast — without touching the safety stack's own
 * defaults.
 *
 * The store is deliberately tiny and self-contained:
 *  - [enabled] is the live, in-memory flag every policy read consults (fast, no disk hit).
 *  - the value is mirrored to SharedPreferences so it survives a process restart.
 *  - [scope] narrows what the bypass actually overrides (parked gate only, or parked + mode).
 *  - listeners fire on every change so the UI, notifications and live screens can react.
 *
 * Fail-safe default: [DEFAULT_ENABLED] is false, so a fresh install behaves exactly like the
 * stock safety gate. Nothing is bypassed until the driver opts in.
 */
object BypassPolicyStore {
    /** What a bypass is allowed to override when [enabled] is true. */
    enum class Scope {
        /** Only the PARKED requirement is lifted. Mode (SAFE/PERSONAL/LAB) still applies. */
        PARKED_ONLY,

        /** Both the PARKED requirement and the mode restriction are lifted. */
        PARKED_AND_MODE
    }

    private const val TAG = "BypassPolicy"
    private const val PREFS_NAME = "autobridge_bypass_policy"
    private const val KEY_ENABLED = "bypass_enabled"
    private const val KEY_SCOPE = "bypass_scope"

    private const val DEFAULT_ENABLED = false
    private val DEFAULT_SCOPE = Scope.PARKED_AND_MODE

    /** Change payload handed to listeners so they do not have to re-read the store. */
    data class State(val enabled: Boolean, val scope: Scope)

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    var enabled: Boolean = DEFAULT_ENABLED
        private set

    @Volatile
    var scope: Scope = DEFAULT_SCOPE
        private set

    val state: State
        get() = State(enabled, scope)

    /** True when the bypass is active and its scope lifts the mode restriction too. */
    val overridesMode: Boolean
        get() = enabled && scope == Scope.PARKED_AND_MODE

    /** True when the bypass is active and lifts the PARKED requirement (any scope does). */
    val overridesParked: Boolean
        get() = enabled

    /**
     * Loads the persisted value once, at process start. Safe to call more than once; later calls
     * only refresh the cached [appContext]. Call from [dev.autobridge.AutoBridgeApplication].
     */
    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
        scope = prefs.getString(KEY_SCOPE, null)?.let { name ->
            Scope.entries.firstOrNull { it.name == name }
        } ?: DEFAULT_SCOPE
        StructuredLog.i(TAG, "init: enabled=$enabled scope=$scope")
    }

    /** Turns the bypass on or off. No-op (no notify) when the value is unchanged. */
    fun setEnabled(enabled: Boolean) {
        if (enabled == this.enabled) return
        this.enabled = enabled
        persist()
        StructuredLog.i(TAG, "setEnabled -> $enabled (scope=$scope)")
        notifyListeners()
    }

    /** Flips the current state and returns the new value. */
    fun toggle(): Boolean {
        setEnabled(!enabled)
        return enabled
    }

    /** Narrows or widens what the bypass overrides. Takes effect immediately for live reads. */
    fun setScope(scope: Scope) {
        if (scope == this.scope) return
        this.scope = scope
        persist()
        StructuredLog.i(TAG, "setScope -> $scope (enabled=$enabled)")
        notifyListeners()
    }

    fun addListener(listener: (State) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners -= listener
    }

    private fun persist() {
        val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs == null) {
            // init() has not run yet — the value still lives in memory and will be used, it just
            // will not survive a restart. Log it rather than silently losing the write.
            StructuredLog.w(TAG, "persist skipped: store not initialised with a context")
            return
        }
        prefs.edit {
            putBoolean(KEY_ENABLED, enabled)
            putString(KEY_SCOPE, scope.name)
        }
    }

    private fun notifyListeners() {
        val snapshot = state
        listeners.forEach { runCatching { it(snapshot) } }
    }
}
