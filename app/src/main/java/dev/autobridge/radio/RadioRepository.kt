package dev.autobridge.radio

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.autobridge.iptv.IptvCatalogData
import dev.autobridge.iptv.IptvChannelQueue
import dev.autobridge.iptv.IptvEntry
import dev.autobridge.iptv.IptvEntryType
import dev.autobridge.iptv.IptvHealthStore
import dev.autobridge.iptv.IptvHistoryStore
import dev.autobridge.iptv.IptvKind
import dev.autobridge.iptv.IptvPlayback
import dev.autobridge.iptv.IptvSource
import dev.autobridge.iptv.IptvSourceType
import dev.autobridge.iptv.RadioPreferencesRepository
import dev.autobridge.iptv.RadioPresetsStore
import dev.autobridge.media.MediaPlaybackClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One snapshot the phone and the car both render, so the two screens always agree.
 *
 * [activeSource] owns the shared session; [selectedSource] is the user's choice (may be FM even
 * though FM is never playable here). [connection] is the Media3 session's connection state. The
 * availability fields are cached once at init from each source — FM is permanently unavailable on
 * this target, offline is unwired this pass.
 */
data class RadioUiState(
    val activeSource: RadioSourceType = RadioSourceType.INTERNET_RADIO,
    val selectedSource: RadioSourceType = RadioSourceType.INTERNET_RADIO,
    val selected: RadioStation? = null,
    val isPlaying: Boolean = false,
    val connection: MediaPlaybackClient.ConnectionState = MediaPlaybackClient.ConnectionState.DISCONNECTED,
    val favorites: List<RadioStation> = emptyList(),
    val presets: List<RadioStation?> = List(RadioPresetsStore.SLOT_COUNT) { null },
    val recents: List<RadioStation> = emptyList(),
    val fmAvailable: Boolean = false,
    val fmUnavailableReason: String? = null,
    val offlineAvailable: Boolean = false,
    val offlineUnavailableReason: String? = null,
)

/**
 * The app-scoped source of truth for the radio domain, over the one shared Media3 session.
 *
 * It owns a single [MediaPlaybackClient] and a single [RadioPlaybackCoordinator] — no second player
 * and no second audio-focus owner. Transport properties are republished from the session's
 * [androidx.media3.common.Player.Listener] plus a 1-second tick, the same way the car dashboard
 * already follows the session. Favourites/recents come from [IptvHistoryStore] (RADIO kind) and
 * presets from [RadioPresetsStore]; nothing here changes those stores.
 */
object RadioRepository {

    private val _state = MutableStateFlow(RadioUiState())
    val state: StateFlow<RadioUiState> = _state.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var started = false

    private val handler = Handler(Looper.getMainLooper())

    private var client: MediaPlaybackClient? = null
    private var coordinatorRef: RadioPlaybackCoordinator? = null

    /** The coordinator, created on first [init]. Audio only ever flows through this one instance. */
    val coordinator: RadioPlaybackCoordinator?
        get() = coordinatorRef

    private val sessionListener = object : androidx.media3.common.Player.Listener {
        override fun onEvents(
            player: androidx.media3.common.Player,
            events: androidx.media3.common.Player.Events,
        ) {
            republish()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            republish()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private const val TICK_MS = 1_000L

    /**
     * Starts the repository against [context]. Caches FM/offline availability once, connects the
     * shared session, and begins republishing. Safe to call more than once; only the first takes
     * effect.
     */
    fun init(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        appContext = app

        val mediaClient = MediaPlaybackClient(app)
        client = mediaClient
        val coord = RadioPlaybackCoordinator(mediaClient)
        coord.onStateChanged = { republish() }
        coordinatorRef = coord
        // Restore the user's persisted selection. switchSource only flips selected/active and never
        // loads audio: an unavailable persisted choice (FM) records as selected while internet
        // radio stays active, which is exactly the honest state.
        coord.switchSource(app, RadioPreferencesRepository.selectedSource(app))

        // Availability is cached once: FM is permanently unavailable here, offline is unwired.
        val fmAvailable = HardwareFmSource.isAvailable(app)
        val fmReason = HardwareFmSource.unavailableReason(app)
        val offlineAvailable = OfflineAudioSource.isAvailable(app)
        val offlineReason = OfflineAudioSource.unavailableReason(app)
        _state.value = _state.value.copy(
            fmAvailable = fmAvailable,
            fmUnavailableReason = fmReason,
            offlineAvailable = offlineAvailable,
            offlineUnavailableReason = offlineReason,
        )

        mediaClient.connect(
            onConnected = {
                mediaClient.player?.addListener(sessionListener)
                republish()
            },
            onError = { republish() },
        )
        handler.postDelayed(tick, TICK_MS)
        republish()
    }

    /** Rebuilds the published snapshot from the session and the stores. Main thread. */
    private fun republish() {
        val ctx = appContext ?: return
        val mediaClient = client
        val coord = coordinatorRef
        val favorites = IptvHistoryStore.favorites(ctx, IptvKind.RADIO).map { RadioHistory.toRadioStation(it) }
        val recents = IptvHistoryStore.recent(ctx, IptvKind.RADIO).map { RadioHistory.toRadioStation(it) }
        val presets = RadioPresetsStore.presets(ctx)
        val selected = currentSelectedStation(mediaClient)
        _state.value = _state.value.copy(
            activeSource = coord?.activeSource() ?: RadioSourceType.INTERNET_RADIO,
            selectedSource = coord?.selectedSource() ?: RadioSourceType.INTERNET_RADIO,
            selected = selected,
            isPlaying = mediaClient?.isPlaying == true,
            connection = mediaClient?.connectionState ?: MediaPlaybackClient.ConnectionState.DISCONNECTED,
            favorites = favorites,
            presets = presets,
            recents = recents,
        )
    }

    private fun currentSelectedStation(mediaClient: MediaPlaybackClient?): RadioStation? {
        val c = mediaClient ?: return null
        val index = c.currentIndex ?: return null
        val uris = c.currentQueueUris()
        val url = uris.getOrNull(index)?.takeIf { it.isNotBlank() } ?: return null
        val title = c.currentTitle ?: url
        return RadioStation(RadioSourceType.INTERNET_RADIO, id = url, title = title, streamUrl = url)
    }

    /** Toggles [station] in RADIO favourites. HARDWARE_FM is never persisted. */
    fun toggleFavorite(context: Context, station: RadioStation): Boolean =
        RadioHistory.toggleFavorite(context, station)

    /** Records [station] as recently played. HARDWARE_FM is never persisted. */
    fun recordPlayback(context: Context, station: RadioStation) =
        RadioHistory.recordPlayback(context, station)

    /**
     * Builds the playback queue around [anchor] from [items] and plays it on the shared session.
     * Uses the phone's dead-stream check — a channel [IptvHealthStore] last saw fail is left out
     * (correction #5, NOT `{ false }`). A null queue (nothing to step to) plays the anchor alone
     * (correction #1). Internet-only: [items]/[anchor] come from the streaming history.
     */
    fun playFromItems(
        anchorStation: RadioStation,
        items: List<IptvHistoryStore.Item>,
        anchorItem: IptvHistoryStore.Item,
    ) {
        val coord = coordinatorRef ?: return
        val isDead = { url: String -> IptvHealthStore.broken(url) != null }
        val cq = IptvChannelQueue.aroundItems(items, anchorItem, isDead)
        if (cq == null) {
            coord.play(anchorStation, queue = listOf(anchorStation), startIndex = 0)
        } else {
            val queue = cq.channels.map { qc ->
                RadioStation(RadioSourceType.INTERNET_RADIO, id = qc.url, title = qc.title, streamUrl = qc.url)
            }
            coord.play(anchorStation, queue, startIndex = cq.startIndex)
        }
    }

    /**
     * The store two-way mapping, kept in one place so READ and WRITE stay symmetric and FM never
     * leaks into persistence.
     */
    object RadioHistory {
        /**
         * READ: a stored RADIO item becomes an internet station. [IptvHistoryStore.Item] has no
         * subtitle, so subtitle is not round-tripped — it stays blank. Never a frequency.
         */
        fun toRadioStation(item: IptvHistoryStore.Item): RadioStation = RadioStation(
            sourceType = RadioSourceType.INTERNET_RADIO,
            id = item.url,
            title = item.title,
            streamUrl = item.url,
            logo = item.logo,
            subtitle = "",
            frequencyKHz = null,
        )

        /**
         * WRITE: a station becomes the (source, entry) pair [IptvHistoryStore] stores. Rejects
         * HARDWARE_FM and blank URLs — FM is never persisted.
         */
        private fun synthForStore(station: RadioStation): Pair<IptvSource, IptvEntry> {
            require(station.sourceType != RadioSourceType.HARDWARE_FM && station.streamUrl.isNotBlank()) {
                "only playable internet/offline stations can be persisted"
            }
            val source = IptvSource(
                id = "radio-virtual",
                name = "Radio",
                kind = IptvKind.RADIO,
                type = IptvSourceType.M3U,
                url = station.streamUrl,
            )
            val entry = IptvEntry(
                id = station.id,
                title = station.title,
                categoryId = IptvCatalogData.ALL_CATEGORY_ID,
                type = IptvEntryType.LIVE,
                url = station.streamUrl,
                logo = station.logo,
                subtitle = station.subtitle,
                playback = IptvPlayback.STREAM,
            )
            return source to entry
        }

        fun toggleFavorite(context: Context, station: RadioStation): Boolean {
            val (source, entry) = synthForStore(station)
            return IptvHistoryStore.toggleFavorite(context, source, entry)
        }

        fun recordPlayback(context: Context, station: RadioStation) {
            val (source, entry) = synthForStore(station)
            IptvHistoryStore.recordPlayback(context, source, entry)
        }
    }
}
