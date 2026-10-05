package dev.autobridge.core.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.autobridge.iptv.IptvKind
import dev.autobridge.iptv.IptvSource
import dev.autobridge.iptv.IptvSourceStore
import dev.autobridge.iptv.IptvSourceType
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device cover for the IPTV credential storage, which only exists on a device: [SecretText]
 * needs the real Android Keystore, so none of this can be asserted from a JVM test.
 *
 * The assertions are deliberately about the bytes on disk rather than about the API round-tripping.
 * A store that encrypted on write and happened to keep a plaintext copy under another key would
 * pass a round-trip test and still leak the password to anything that reads the prefs file - which
 * is the thing this is here to prevent.
 */
@RunWith(AndroidJUnit4::class)
class CredentialStorageTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prefsName = "autobridge_test_secrets"
    private val prefs get() = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    @Before
    @After
    fun clean() {
        prefs.edit().clear().commit()
        context.getSharedPreferences("autobridge_iptv_sources", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun roundTripsThroughCiphertext() {
        SecretPrefs.write(prefs, prefsName, "value", "hunter2")

        val raw = prefs.getString("value", null)
        assertTrue("stored value must be marked ciphertext, was $raw", SecretText.isEncrypted(raw!!))
        assertFalse("plaintext must not survive in the stored value", raw.contains("hunter2"))
        assertEquals("hunter2", SecretPrefs.read(prefs, prefsName, "value"))
    }

    @Test
    fun migratesAPlaintextValueWrittenByAnOlderBuild() {
        prefs.edit().putString("value", "legacy-secret").commit()

        // The value a pre-encryption build left behind still reads back...
        assertEquals("legacy-secret", SecretPrefs.read(prefs, prefsName, "value"))
        // ...and reading it is what takes it off disk in the clear.
        val raw = prefs.getString("value", null)!!
        assertTrue(SecretText.isEncrypted(raw))
        assertFalse(raw.contains("legacy-secret"))
    }

    @Test
    fun dropsCiphertextItCannotRead() {
        // The shape of a file restored onto a device whose Keystore never held the key.
        prefs.edit().putString("value", SecretText.PREFIX + "Zm9yZWlnbi1jaXBoZXJ0ZXh0").commit()

        assertEquals("", SecretPrefs.read(prefs, prefsName, "value"))
        assertNull("an unreadable value must not be kept", prefs.getString("value", null))
    }

    @Test
    fun blankWriteClearsTheEntry() {
        SecretPrefs.write(prefs, prefsName, "value", "something")
        SecretPrefs.write(prefs, prefsName, "value", "")

        assertNull(prefs.getString("value", null))
        assertEquals("", SecretPrefs.read(prefs, prefsName, "value"))
    }

    /**
     * The regression that started all of this: an Xtream account saved from the IPTV screen used to
     * land in `shared_prefs/autobridge_iptv_sources.xml` as readable JSON, password included.
     */
    @Test
    fun savedIptvSourceLeavesNoCredentialInItsPrefsFile() {
        val source = IptvSource(
            id = IptvSourceStore.newId(),
            name = "Test portal",
            kind = IptvKind.TV,
            type = IptvSourceType.XTREAM,
            url = "http://portal.example:8080",
            username = "user-7f3a",
            password = "pass-9b21"
        )
        IptvSourceStore.save(context, source)

        val stored = IptvSourceStore.find(context, source.id)
        assertEquals("pass-9b21", stored?.password)
        assertEquals("user-7f3a", stored?.username)

        val file = File(context.dataDir, "shared_prefs/autobridge_iptv_sources.xml")
        val text = if (file.exists()) file.readText() else ""
        assertFalse("password found in $file", text.contains("pass-9b21"))
        assertFalse("username found in $file", text.contains("user-7f3a"))
        assertFalse("portal URL found in $file", text.contains("portal.example"))
    }
}
