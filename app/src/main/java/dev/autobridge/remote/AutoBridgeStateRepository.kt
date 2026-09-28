package dev.autobridge.remote

import dev.autobridge.core.state.RuntimeContextStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-global reactive holder for [AutoBridgeState]. Mirrors the pattern of
 * [dev.autobridge.core.state.RuntimeContextStore]: a single [MutableStateFlow] that both the phone
 * UI and the Android Auto screens observe/mutate. Because everything runs in one process, this is
 * all the "sync" that is needed — no sockets or IPC.
 *
 * Feature controllers/screens call the focused mutators as their own state changes; the router and
 * both UIs collect [state]. Thread-safe (StateFlow updates are atomic).
 */
object AutoBridgeStateRepository {
    private val _state = MutableStateFlow(
        AutoBridgeState(androidAutoConnected = RuntimeContextStore.context.value.connected)
    )
    val state: StateFlow<AutoBridgeState> = _state.asStateFlow()

    val current: AutoBridgeState get() = _state.value

    private fun update(transform: (AutoBridgeState) -> AutoBridgeState) {
        val next = transform(_state.value)
        if (next != _state.value) _state.value = next
    }

    fun setCurrentScreen(screen: RemoteScreen) = update { it.copy(currentScreen = screen) }

    fun setBrowser(url: String?, title: String?, loading: Boolean) = update {
        it.copy(
            currentScreen = if (it.currentScreen == RemoteScreen.NONE) RemoteScreen.BROWSER else it.currentScreen,
            currentUrl = url,
            browserTitle = title,
            browserLoading = loading
        )
    }

    fun setBrowserActive(url: String?, title: String?) = update {
        it.copy(currentScreen = RemoteScreen.BROWSER, currentUrl = url, browserTitle = title)
    }

    fun setMirrorStatus(status: MirrorStatus) = update {
        val screen = if (status == MirrorStatus.ACTIVE) RemoteScreen.MIRROR else it.currentScreen
        it.copy(mirrorStatus = status, currentScreen = screen)
    }

    fun setMedia(title: String?, artist: String?, playing: Boolean) = update {
        it.copy(mediaTitle = title, mediaArtist = artist, mediaPlaying = playing)
    }

    fun setMediaActive(title: String?, artist: String?, playing: Boolean) = update {
        it.copy(currentScreen = RemoteScreen.MEDIA, mediaTitle = title, mediaArtist = artist, mediaPlaying = playing)
    }

    fun setAgentReady(ready: Boolean) = update { it.copy(agentReady = ready) }

    fun setAndroidAutoConnected(connected: Boolean) = update {
        // On disconnect, drop the live surface state so the UI does not show a stale screen.
        if (!connected) {
            it.copy(androidAutoConnected = false, currentScreen = RemoteScreen.NONE)
        } else {
            it.copy(androidAutoConnected = true)
        }
    }
}
