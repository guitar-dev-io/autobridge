package dev.autobridge.core.crypto

import android.content.SharedPreferences
import androidx.core.content.edit
import dev.autobridge.logging.StructuredLog

/**
 * Reads and writes the SharedPreferences values that must be encrypted at rest, on top of
 * [SecretText].
 *
 * Stores keep their existing shape: they still serialise their own JSON and still own their prefs
 * file. Only the string that reaches disk changes, which is why both IPTV stores could adopt this
 * without touching their models - each has exactly one place where a JSON blob is read and one
 * where it is written.
 *
 * Two behaviours are worth knowing before using it:
 *
 *  - **Migration is automatic.** A value an older build wrote in the clear is recognised by the
 *    missing [SecretText.PREFIX], returned to the caller as usual, and rewritten encrypted on the
 *    spot. No store needs migration code of its own, and a user updating the app keeps their data.
 *  - **It never falls back to plaintext.** If the Keystore cannot produce a key, the value is held
 *    in memory for the life of the process and nothing is written. The session keeps working; the
 *    value is lost on the next process start. That is the deliberate trade: losing a saved password
 *    on a device with a broken Keystore beats writing it to disk in the clear.
 */
object SecretPrefs {
    private const val TAG = "SecretPrefs"

    /**
     * Values that could not be encrypted, kept for this process only. Keyed by prefs file and key,
     * because the fallback has to survive a store re-reading its own value within the session.
     */
    private val inMemory = mutableMapOf<String, String>()

    /** The decrypted value for [key], or "" when there is none. [name] is the prefs file name. */
    @Synchronized
    fun read(prefs: SharedPreferences, name: String, key: String): String {
        val raw = prefs.getString(key, null).orEmpty()
        if (raw.isBlank()) return inMemory[cacheKey(name, key)].orEmpty()
        if (!SecretText.isEncrypted(raw)) {
            // Written by a build before this file existed. Hand it back, then take it off disk.
            StructuredLog.i(TAG, "migrating plaintext '$name/$key' to encrypted storage")
            write(prefs, name, key, raw)
            return raw
        }
        val plain = decryptOrDrop(prefs, name, key, raw)
        return plain ?: inMemory[cacheKey(name, key)].orEmpty()
    }

    /** Encrypts and stores [value], or clears the entry when [value] is blank. */
    @Synchronized
    fun write(prefs: SharedPreferences, name: String, key: String, value: String) {
        val cache = cacheKey(name, key)
        if (value.isBlank()) {
            inMemory.remove(cache)
            prefs.edit { remove(key) }
            return
        }
        val sealed = SecretText.encrypt(value)
        if (sealed == null) {
            // Keep the session usable, but do not leave a readable copy - including any older
            // ciphertext or plaintext still sitting under this key.
            inMemory[cache] = value
            prefs.edit { remove(key) }
            StructuredLog.e(TAG, "no Keystore key; '$name/$key' kept in memory only, not stored")
            return
        }
        inMemory.remove(cache)
        prefs.edit { putString(key, sealed) }
    }

    /**
     * Drops an entry whose ciphertext cannot be read any more, so the app stops carrying a value it
     * can never use again. This is the path a file restored onto another device takes, or one whose
     * key the system discarded; the user re-enters the credential, which is the intended outcome.
     */
    private fun decryptOrDrop(
        prefs: SharedPreferences,
        name: String,
        key: String,
        raw: String
    ): String? {
        SecretText.decrypt(raw)?.let { return it }
        StructuredLog.w(TAG, "'$name/$key' is no longer decryptable; dropping it")
        prefs.edit { remove(key) }
        return null
    }

    private fun cacheKey(name: String, key: String) = "$name/$key"
}
