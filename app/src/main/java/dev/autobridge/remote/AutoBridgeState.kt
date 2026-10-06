package dev.autobridge.remote

/** The feature currently in the foreground on the car surface. */
enum class RemoteScreen { NONE, HOME, BROWSER, MIRROR, MEDIA, AGENT, SETTINGS }

enum class MirrorStatus { INACTIVE, READY, ACTIVE }

/**
 * Reactive snapshot of what AutoBridge is doing right now, observed by BOTH the Mobile Remote and
 * the Android Auto UI so the two stay in sync in real time (two-way: whichever surface changes
 * something writes here, the other reads it).
 *
 * This holds only *live* session state; durable data stays in its own stores.
 */
data class AutoBridgeState(
    val currentScreen: RemoteScreen = RemoteScreen.NONE,

    // Browser
    val currentUrl: String? = null,
    val browserTitle: String? = null,
    val browserLoading: Boolean = false,

    // Mirror
    val mirrorStatus: MirrorStatus = MirrorStatus.INACTIVE,

    // Media
    val mediaTitle: String? = null,
    val mediaArtist: String? = null,
    val mediaPlaying: Boolean = false,

    // Agent
    val agentReady: Boolean = true,

    // Connection
    val androidAutoConnected: Boolean = false
)
