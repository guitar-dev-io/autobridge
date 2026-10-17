package dev.autobridge.radio

import android.content.ContextWrapper
import android.content.SharedPreferences
import dev.autobridge.media.RadioTransportClient

/**
 * JVM test doubles for the radio unit tests.
 *
 * These run as plain JUnit tests (no Robolectric), so anything that would reach a live Android
 * service — the Media3 session, the Keystore, a real SharedPreferences file — is replaced here with
 * an in-memory stand-in. The doubles mirror only the behaviour the code under test observes.
 */

/**
 * A [RadioTransportClient] that records what the coordinator asks of it instead of touching a
 * session. [queueUris] is what the coordinator reads back as the "live" queue; the last
 * [playPlaylist] call is captured so a test can assert the mapped URIs/titles and the coerced
 * start index.
 */
class FakeRadioTransportClient(
    var queueUris: List<String> = emptyList(),
) : RadioTransportClient {

    data class PlayCall(val uris: List<String>, val startIndex: Int, val titles: List<String>)

    var lastPlay: PlayCall? = null
    var playCount = 0
    var pauseCount = 0
    var resumeCount = 0
    var nextCount = 0
    var previousCount = 0
    var stopCount = 0

    override fun currentQueueUris(): List<String> = queueUris

    override fun playPlaylist(uris: List<String>, startIndex: Int, titles: List<String>) {
        playCount++
        lastPlay = PlayCall(uris, startIndex, titles)
    }

    override fun pause() { pauseCount++ }
    override fun resume() { resumeCount++ }
    override fun next() { nextCount++ }
    override fun previous() { previousCount++ }
    override fun stop() { stopCount++ }
}

/**
 * A [android.content.Context] good enough for the stores and sources under test: it hands back a
 * working in-memory [SharedPreferences] per name, so [dev.autobridge.core.crypto.SecretPrefs]
 * round-trips through its own in-memory fallback (the Keystore is unavailable on the JVM, which is
 * the expected no-crash path).
 */
class FakeRadioContext : ContextWrapper(null) {
    private val prefsByName = mutableMapOf<String, FakeSharedPreferences>()

    override fun getApplicationContext(): android.content.Context = this

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefsByName.getOrPut(name) { FakeSharedPreferences() }

    // getString is final on ContextWrapper and cannot be overridden; under the mockable android.jar
    // (testOptions.unitTests.isReturnDefaultValues) it returns a default rather than touching a
    // base context, which is all the sources' unavailableReason() needs in a JVM test.

    /** Seeds a raw stored value, bypassing the store — used to inject corrupt/legacy entries. */
    fun putRaw(name: String, key: String, value: String) {
        (prefsByName.getOrPut(name) { FakeSharedPreferences() })
            .edit().putString(key, value).apply()
    }
}

/** A minimal in-memory [SharedPreferences], enough for [dev.autobridge.core.crypto.SecretPrefs]. */
class FakeSharedPreferences : SharedPreferences {
    private val map = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (map[key] as? MutableSet<String>) ?: defValues
    override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = Editor()

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clear = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply { pending[key] = values }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { pending[key] = value }
        override fun remove(key: String): SharedPreferences.Editor = apply { removals += key }
        override fun clear(): SharedPreferences.Editor = apply { clear = true }

        override fun commit(): Boolean { applyNow(); return true }
        override fun apply() { applyNow() }

        private fun applyNow() {
            if (clear) map.clear()
            removals.forEach { map.remove(it) }
            pending.forEach { (k, v) -> map[k] = v }
        }
    }
}
