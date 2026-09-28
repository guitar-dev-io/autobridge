package dev.autobridge.remote

import android.content.Context
import dev.autobridge.core.state.RuntimeContextStore
import dev.autobridge.media.MediaPlaybackClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-global wiring for the Mobile Remote & Command system.
 *
 * Responsibilities:
 *  - Restore persistent stores (history, quick commands, settings) once.
 *  - Own the SINGLE subscription to [AutoBridgeCommandBus.commands] and route each command through
 *    [AutoBridgeCommandRouter] on the main dispatcher (car/media controllers require main thread).
 *  - Keep [AutoBridgeStateRepository]'s androidAutoConnected flag in sync with the runtime context.
 *
 * Initialized from [dev.autobridge.MainActivity] (and safe to call again from the car session);
 * [ensureStarted] is idempotent so repeated calls do not create duplicate collectors.
 */
object RemoteRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var started = false

    @Volatile
    private var mediaClient: MediaPlaybackClient? = null

    fun ensureStarted(context: Context) {
        if (started) {
            syncConnection()
            return
        }
        synchronized(this) {
            if (started) return
            started = true
        }
        val appContext = context.applicationContext

        CommandHistoryStore.restore(appContext)
        QuickCommandStore.restore(appContext)
        RemoteSettingsStore.restore(appContext)

        // Single consumer of the command bus.
        scope.launch {
            AutoBridgeCommandBus.commands.collect { command ->
                AutoBridgeCommandRouter.execute(appContext, command, mediaClient)
            }
        }

        // Keep connection state mirrored so the remote shows the correct online/offline status.
        scope.launch {
            RuntimeContextStore.context.collect { ctx ->
                AutoBridgeStateRepository.setAndroidAutoConnected(ctx.connected)
            }
        }
    }

    /**
     * Lets the phone UI share its already-connected [MediaPlaybackClient] with the router so media
     * commands reuse the same controller/session (no second connection).
     */
    fun attachMediaClient(client: MediaPlaybackClient?) {
        mediaClient = client
    }

    private fun syncConnection() {
        AutoBridgeStateRepository.setAndroidAutoConnected(RuntimeContextStore.context.value.connected)
    }
}
