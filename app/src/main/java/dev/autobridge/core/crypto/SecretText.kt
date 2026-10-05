package dev.autobridge.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.autobridge.logging.StructuredLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM encryption for the few strings that must not sit on disk in the clear - today the IPTV
 * provider credentials and the stream URLs that embed them.
 *
 * The key lives in the Android Keystore, so it never appears in the app's own storage and cannot be
 * read out of the device. Three properties follow from that, and they are the reason this exists:
 *
 *  - uninstall removes the key along with the app, so any copy of the ciphertext that outlives the
 *    install - a stray backup, a forensic image of the data partition - is unreadable afterwards;
 *  - a file copied to another device is equally unreadable there, because the key does not travel;
 *  - reading a prefs file off a rooted device yields ciphertext, not a password.
 *
 * Not [androidx.security.crypto.EncryptedSharedPreferences]: that library is deprecated and would
 * pull Tink in for what is a dozen lines against the platform Keystore, which has been available
 * since long before this app's minSdk 29.
 *
 * Deliberately no user-authentication requirement on the key. Playback has to survive a locked
 * screen and a car session that starts with the phone in a pocket, so a key that needed the
 * keyguard would break the feature it is protecting. The threat this answers is a copy of the data
 * leaving the device, not an attacker holding an unlocked phone.
 */
object SecretText {
    private const val TAG = "SecretText"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "autobridge_secret_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    /**
     * Marks a value this object produced. It is what makes the migration off plaintext decidable:
     * a stored string without the prefix is a value an older build wrote in the clear, and the
     * caller rewrites it encrypted.
     */
    const val PREFIX = "enc1:"

    /** True when [value] is ciphertext from this object rather than a legacy plaintext value. */
    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    /**
     * Encrypts [plain], returning `"enc1:"` + base64 of `iv || ciphertext`.
     *
     * Returns null when the platform Keystore refuses to produce a key or a cipher - rare, but real
     * on some OEM builds after a keyguard change. The caller decides what to do with that; it must
     * not be read as "nothing to encrypt".
     */
    fun encrypt(plain: String): String? {
        val key = secretKey() ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            require(iv.size == GCM_IV_BYTES) { "unexpected GCM iv size ${iv.size}" }
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + body, Base64.NO_WRAP)
        }.onFailure { StructuredLog.w(TAG, "encrypt failed: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    /**
     * Decrypts a value produced by [encrypt].
     *
     * Returns null when the value is not decryptable here: the key was dropped (app data cleared
     * through the system, keystore reset, file restored onto another device) or the ciphertext is
     * damaged. That is an expected outcome, not a crash - the caller treats it as "no stored value"
     * and the user re-enters the credential, which is exactly the intended behaviour when the
     * material arrives from somewhere it should not have.
     */
    fun decrypt(stored: String): String? {
        if (!isEncrypted(stored)) return null
        val key = secretKey() ?: return null
        return runCatching {
            val bytes = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            require(bytes.size > GCM_IV_BYTES) { "ciphertext too short" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, bytes, 0, GCM_IV_BYTES)
            )
            String(
                cipher.doFinal(bytes, GCM_IV_BYTES, bytes.size - GCM_IV_BYTES),
                Charsets.UTF_8
            )
        }.onFailure { StructuredLog.w(TAG, "decrypt failed: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    /**
     * Deletes the key, making every value encrypted with it permanently unreadable.
     *
     * Called by the in-app "clear all data" path: wiping the prefs files that hold the ciphertext
     * and leaving the key behind would be harmless but untidy, and dropping the key makes the wipe
     * final even for a copy of those files taken beforehand. A later [encrypt] simply generates a
     * fresh key.
     */
    @Synchronized
    fun forget() {
        runCatching {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (store.containsAlias(ALIAS)) {
                store.deleteEntry(ALIAS)
                StructuredLog.i(TAG, "secret key deleted")
            }
        }.onFailure { StructuredLog.w(TAG, "key delete failed: ${it.javaClass.simpleName}") }
    }

    /**
     * The app's AES key, generated on first use.
     *
     * Synchronized because two sources can be saved from different threads on first launch and two
     * concurrent generations would have one overwrite the other's key - which would leave whatever
     * the loser wrote undecryptable. A damaged entry (wrong type, unusable key) is deleted and
     * regenerated once rather than failing for the life of the install.
     */
    @Synchronized
    private fun secretKey(): SecretKey? {
        existingKey()?.let { return it }
        return generateKey()
    }

    private fun existingKey(): SecretKey? = runCatching {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }.onFailure {
        StructuredLog.w(TAG, "key load failed, regenerating: ${it.javaClass.simpleName}")
        runCatching {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
        }
    }.getOrNull()

    private fun generateKey(): SecretKey? = runCatching {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // A reused IV with GCM is a key-recovery break, so let the provider pick one per
                // operation and refuse any caller that tries to supply its own.
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        generator.generateKey()
    }.onFailure { StructuredLog.e(TAG, "key generation failed: ${it.javaClass.simpleName}") }
        .getOrNull()
}
