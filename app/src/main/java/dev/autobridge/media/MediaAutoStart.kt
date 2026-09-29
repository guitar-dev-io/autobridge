package dev.autobridge.media

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.autobridge.display.StructuredLog

/**
 * Brings the media session up when the phone connects to a car, so the head unit finds it already
 * there instead of only after something is played from the phone.
 *
 * Off by default. It starts the session and nothing else: no track is chosen and nothing is played,
 * because a car connecting is not a request to make noise.
 */
object MediaAutoStart {
    private const val PREFS_NAME = "autobridge_media_autostart"
    private const val KEY_ENABLED = "start_on_bluetooth"

    /** How long the session is held up before the binding is dropped again. */
    private const val HOLD_MS = 15_000L

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) =
        prefs(context).edit { putBoolean(KEY_ENABLED, value) }

    /**
     * Android 12 made the Bluetooth connect broadcast permission-protected, so without this the
     * receiver is simply never called — a setting that silently does nothing.
     */
    fun hasBluetoothPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Starts [MediaPlaybackService] by connecting a controller to it, then lets go.
     *
     * A controller connection binds the service, which is what a background receiver is allowed to
     * do; `startService` from here would hit the background-start restriction, and a
     * `MediaSessionService` that is started without playing cannot go foreground to satisfy it.
     * The binding is released after [HOLD_MS] so an idle session does not hold the process open —
     * by then the car has enumerated it.
     */
    fun startSession(context: Context) {
        val appContext = context.applicationContext
        val token = SessionToken(appContext, ComponentName(appContext, MediaPlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        future.addListener(
            {
                val controller = runCatching { future.get() }.getOrNull()
                if (controller == null) {
                    StructuredLog.w("MEDIA", "auto-start: could not reach the media session")
                    return@addListener
                }
                StructuredLog.i("MEDIA", "auto-start: session is up")
                Handler(Looper.getMainLooper()).postDelayed({ controller.release() }, HOLD_MS)
            },
            { command -> Handler(Looper.getMainLooper()).post(command) }
        )
    }

    /**
     * Manifest-registered; `ACL_CONNECTED` is one of the implicit broadcasts that still reaches a
     * declared receiver, which is the point — the app is not running when the car is switched on.
     */
    class BluetoothReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
            if (!isEnabled(context)) return
            // No device name is read: it would need BLUETOOTH_CONNECT for its own sake and adds
            // nothing, since the user asked for this on any car connection.
            StructuredLog.i("MEDIA", "auto-start: bluetooth device connected")
            runCatching { startSession(context) }
                .onFailure { StructuredLog.w("MEDIA", "auto-start failed: ${it.message}") }
        }
    }
}
