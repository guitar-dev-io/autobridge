package dev.autobridge.car

import android.content.Context
import androidx.car.app.connection.CarConnection
import dev.autobridge.core.state.RuntimeContextStore
import dev.autobridge.logging.StructuredLog
import dev.autobridge.remote.CarScreenController

/**
 * Decides whether the phone is connected to the car, for every phone screen that says so.
 *
 * Only a live [AutoBridgeSession] used to count as a connection. But the car can be connected
 * with that session gone or never started: the driver using Bridge Web, Bridge Mirror or Duo
 * Screen (each its own entry point), sitting on Maps, or Android Auto having just recycled the
 * session. In all of those the phone read "Not connected" while the head unit was plainly running.
 *
 * The Android Auto host's own connection signal ([CarConnection]) is now the main source. It
 * reports the connection whichever of this app's entry points — if any — is on the car. A live
 * session still counts too, so a host that is slow to broadcast cannot make a running session
 * look disconnected.
 */
object CarConnectionMonitor {
    private const val TAG = "CarConnection"

    @Volatile
    private var hostConnected = false

    private var installed = false

    /** Starts listening to the host. Main thread; called once from the Application. */
    fun install(context: Context) {
        if (installed) return
        installed = true
        runCatching {
            CarConnection(context.applicationContext).type.observeForever { type ->
                hostConnected = type != null && type != CarConnection.CONNECTION_TYPE_NOT_CONNECTED
                StructuredLog.i(TAG, "host connection type=$type")
                publish()
            }
        }.onFailure { StructuredLog.w(TAG, "car connection signal unavailable: ${it.message}") }
    }

    /** Re-derives the connected flag; call whenever the AutoBridge session comes or goes. */
    fun publish() {
        RuntimeContextStore.setConnected(hostConnected || CarScreenController.isConnected)
    }
}
