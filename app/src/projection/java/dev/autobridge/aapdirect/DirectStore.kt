package dev.autobridge.aapdirect

import android.content.Context
import dev.autobridge.aap.AapCredentials
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What Android Auto direct keeps between sessions: the certificate the user uploaded and the
 * display density chosen for the car screen. The certificate and key live in app-private storage
 * as one PEM file; nothing is sent anywhere except to the car during the TLS handshake.
 */
object DirectStore {
    private const val PREFS = "aa_direct"
    private const val KEY_DPI = "dpi"
    private const val KEY_CERT_NAME = "cert_name"
    private const val KEY_SEND_AUDIO = "send_audio"
    private const val KEY_SHOW_PHONE = "show_phone"
    private const val KEY_DEVICE_ID = "device_id"
    const val DPI_MIN = 100
    const val DPI_MAX = 480
    const val DPI_STEP = 20

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun credentialsFile(context: Context) = File(File(context.filesDir, "aa_direct"), "credentials.pem")

    /** 0 = use the density the car asks for. */
    fun dpi(context: Context): Int = prefs(context).getInt(KEY_DPI, 0)

    fun setDpi(context: Context, dpi: Int) {
        prefs(context).edit().putInt(KEY_DPI, dpi.coerceIn(DPI_MIN, DPI_MAX)).apply()
    }

    /** Open the car's music channel and send the phone's media sound to it. Off by default. */
    fun sendAudio(context: Context): Boolean = prefs(context).getBoolean(KEY_SEND_AUDIO, false)

    fun setSendAudio(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_SEND_AUDIO, on).apply()
    }

    /**
     * What the car shows: false = AutoBridge's own screens on a display of their own; true = the
     * whole phone screen, mirrored, so any app can be used (touch then needs Shizuku or the
     * accessibility service).
     */
    fun showPhoneScreen(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_PHONE, false)

    fun setShowPhoneScreen(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_PHONE, on).apply()
    }

    /** A random id for this phone, kept so a car recognises it from one drive to the next. */
    fun deviceId(context: Context): String {
        val prefs = prefs(context)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    fun hasCredentials(context: Context): Boolean = credentialsFile(context).isFile

    fun certificateName(context: Context): String? = prefs(context).getString(KEY_CERT_NAME, null)

    /** Parses before saving, so a bad pair is refused at upload time instead of in the car. */
    fun saveCredentials(context: Context, pemText: String, displayName: String): AapCredentials {
        val parsed = AapCredentials.fromPem(pemText)
        val file = credentialsFile(context)
        file.parentFile?.mkdirs()
        file.writeText(pemText)
        prefs(context).edit().putString(KEY_CERT_NAME, displayName).apply()
        return parsed
    }

    fun loadCredentials(context: Context): AapCredentials? {
        val file = credentialsFile(context)
        if (!file.isFile) return null
        return runCatching { AapCredentials.fromPem(file.readText()) }.getOrNull()
    }

    fun clearCredentials(context: Context) {
        credentialsFile(context).delete()
        prefs(context).edit().remove(KEY_CERT_NAME).apply()
    }
}

/** The connection log shown and copied from the phone screen. Last [MAX] lines, newest last. */
object DirectLog {
    private const val MAX = 600
    private val lines = ArrayDeque<String>()
    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val listeners = LinkedHashSet<() -> Unit>()

    @Synchronized
    fun add(line: String) {
        lines.addLast("${format.format(Date())} $line")
        while (lines.size > MAX) lines.removeFirst()
        android.util.Log.i("AaDirect", line)
        listeners.toList().forEach { it() }
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()

    @Synchronized
    fun listen(listener: () -> Unit) { listeners += listener }

    @Synchronized
    fun unlisten(listener: () -> Unit) { listeners -= listener }
}
