package dev.autobridge.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import dev.autobridge.bridge.engine.BrowserPlaybackEngine
import dev.autobridge.bridge.engine.NativePlaybackEngine
import dev.autobridge.bridge.engine.PlaybackEngine
import dev.autobridge.bridge.engine.RemoteStreamPlaybackEngine
import dev.autobridge.browser.BrowserPlayQueue
import dev.autobridge.core.state.RecentActivityStore
import dev.autobridge.core.state.RuntimeContextStore
import dev.autobridge.remote.CarScreenController
import dev.autobridge.remotestream.ControlMessage
import dev.autobridge.remotestream.RemoteStreamConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one component that knows what the bridge is doing.
 *
 * ## Why it exists
 *
 * Before this, "is the car connected", "what is playing" and "what happens to a link sent while
 * disconnected" each had two or three answers living in different places — an activity, a car
 * screen, and a store that only one of them wrote to. They disagreed often enough that the phone
 * would offer to control something the car had already closed. This object owns those answers,
 * and the UIs read them instead of keeping their own.
 *
 * It holds the live session; durable state stays in the stores that already own it
 * ([BrowserPlayQueue], [RecentActivityStore], [BridgeStore],
 * [dev.autobridge.entertainment.WebBookmarkStore]). The split is the same one the rest of the app
 * uses, and it is what lets a process restart lose nothing that mattered.
 *
 * ## Connection
 *
 * A live car session *is* the Android Auto connection — `AutoBridgeSession` already establishes
 * that and writes it to [RuntimeContextStore]. This does not try to detect it a second time; it
 * is told, by [onCarConnected] and [onCarDisconnected], and cross-checks against
 * [CarScreenController] because a replacement session can be created before the outgoing one is
 * destroyed.
 *
 * ## Threading
 *
 * Main thread only. Engines call car services and a MediaController, both of which require it,
 * and [post] exists so a callback arriving from a transport thread can hop here rather than each
 * engine inventing its own hop.
 */
object AutoBridgeSessionManager {

    private val main = Handler(Looper.getMainLooper())

    /** Everything a UI needs to render the bridge, in one value. */
    data class SessionState(
        val connected: Boolean = false,
        val hasSurface: Boolean = false,
        val engine: EngineKind = EngineKind.UNSUPPORTED,
        val playback: BridgePlaybackState = BridgePlaybackState.IDLE,
        val source: BridgeSource? = null,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val error: BridgeError? = null,
        val queueSize: Int = 0,
        val pending: BridgeSource? = null
    ) {
        val isIdle: Boolean get() = source == null && playback == BridgePlaybackState.IDLE
        val isPlaying: Boolean get() = playback == BridgePlaybackState.PLAYING
    }

    /** What happened to a Send-to-Car request, as the sender should report it. */
    sealed interface SendResult {
        /** Opened on the car now, on [engine]. */
        data class Opened(val engine: EngineKind) : SendResult

        /** The car is not connected; held and opened automatically when it is. */
        data object Pending : SendResult

        data class Refused(val error: BridgeError) : SendResult
    }

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    val current: SessionState get() = _state.value

    private var appContext: Context? = null
    private var engine: PlaybackEngine? = null

    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    /** Guards against a fallback loop: one step down per source, never a cycle. */
    private var fallbackUsed = false

    // --------------------------------------------------------------------------- lifecycle

    /** Idempotent; safe from both the phone process start and the car session. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        val connected = RuntimeContextStore.context.value.connected || CarScreenController.isConnected
        update {
            it.copy(
                connected = connected,
                queueSize = BrowserPlayQueue.size(context),
                pending = BridgeStore.pending(context)
            )
        }
        BridgeLog.i("session.initialized", "connected" to connected, "queue" to current.queueSize)
    }

    /**
     * Called when a car session starts.
     *
     * The pending Send-to-Car request is consumed here, which is the whole point of holding one:
     * the user shared a link at a time when there was nothing to send it to, and the first thing
     * that should happen on plugging in is that it opens. The last session is *not* auto-resumed
     * — reconnecting and having something start playing unasked is startling, and the snapshot
     * stays available for an explicit resume instead.
     */
    fun onCarConnected(context: Context) {
        initialize(context)
        update { it.copy(connected = true) }
        BridgeLog.i("car.connected")

        val pending = BridgeStore.pending(context)
        if (pending != null) {
            BridgeLog.i("car.pending_replay", "url" to pending.url)
            BridgeStore.setPending(context, null)
            update { it.copy(pending = null) }
            // The car session is still wiring its first screen up at this point; opening into a
            // ScreenManager mid-construction is how the push gets lost. One hop is enough.
            main.post { open(context, pending.copy(origin = BridgeSource.Origin.SHARE)) }
        }
    }

    fun onCarDisconnected(context: Context) {
        saveSnapshot(context)
        engine?.release()
        engine = null
        surface = null
        update {
            it.copy(
                connected = CarScreenController.isConnected,
                hasSurface = false,
                engine = EngineKind.UNSUPPORTED,
                playback = BridgePlaybackState.IDLE,
                source = null
            )
        }
        BridgeLog.i("car.disconnected", "stillConnected" to CarScreenController.isConnected)
    }

    // ------------------------------------------------------------------------- send to car

    /**
     * The single entry point for "put this on the car", whatever asked for it — the share sheet,
     * the phone controller, a car row, the queue.
     *
     * When nothing is connected the request is stored rather than refused, because the usual
     * sequence is "share a link, then walk to the car": failing at the moment of sharing would
     * make the feature useless exactly when it is most convenient.
     */
    fun sendToCar(context: Context, source: BridgeSource): SendResult {
        initialize(context)
        val normalized = ShareIntake.normalize(source.url)
        if (normalized == null) {
            BridgeLog.w("send.invalid", "url" to source.url)
            return SendResult.Refused(BridgeError(BridgeErrorType.UNSUPPORTED_CONTENT, "unparseable URL"))
        }
        val resolved = source.copy(url = normalized)

        val decision = ContentRouter.explain(resolved, RemoteStreamConfig.isAvailable(context))
        if (decision.engine == EngineKind.UNSUPPORTED) {
            val error = BridgeError(
                decision.error ?: BridgeErrorType.UNSUPPORTED_CONTENT,
                "router: ${decision.reason}"
            )
            BridgeLog.w("send.refused", "url" to resolved.url, "reason" to decision.reason)
            update { it.copy(error = error) }
            return SendResult.Refused(error)
        }

        recordRecent(context, resolved)

        if (!isConnected()) {
            BridgeStore.setPending(context, resolved)
            update { it.copy(pending = resolved) }
            BridgeLog.i("send.pending", "url" to resolved.url, "engine" to decision.engine)
            return SendResult.Pending
        }

        open(context, resolved)
        return SendResult.Opened(decision.engine)
    }

    /**
     * Routes [source] to an engine and opens it there.
     *
     * The router's choice is confirmed against the engine's own [PlaybackEngine.canHandle] before
     * being committed to, so a capability the router cannot see — a remote host that is
     * configured but disabled, say — does not produce an engine that immediately fails.
     */
    fun open(context: Context, source: BridgeSource) {
        val remoteAvailable = RemoteStreamConfig.isAvailable(context)
        val decision = ContentRouter.explain(source, remoteAvailable)
        BridgeLog.i(
            "route.decided",
            "url" to source.url,
            "engine" to decision.engine,
            "reason" to decision.reason,
            "remoteAvailable" to remoteAvailable
        )
        if (decision.engine == EngineKind.UNSUPPORTED) {
            update {
                it.copy(
                    error = BridgeError(decision.error ?: BridgeErrorType.UNSUPPORTED_CONTENT),
                    source = source
                )
            }
            return
        }
        fallbackUsed = false
        openOn(context, decision.engine, source)
    }

    private fun openOn(context: Context, kind: EngineKind, source: BridgeSource) {
        val next = engineFor(context, kind)
        if (!next.canHandle(source)) {
            BridgeLog.w("route.engine_declined", "engine" to kind, "url" to source.url)
            if (!stepDown(context, kind, source)) {
                update { it.copy(error = BridgeError(BridgeErrorType.UNSUPPORTED_CONTENT, "no engine accepted it")) }
            }
            return
        }
        BridgeLog.i("engine.selected", "engine" to kind, "url" to source.url)
        update { it.copy(error = null, source = source, engine = kind) }
        // The surface-drawing engines need the car player screen in front of them; the browser
        // engine brings its own screen up through CarScreenController.requireBrowser(). Pushing
        // before open() means the surface callback has already fired by the time the engine
        // wants it, which is what stops the first frame landing nowhere.
        if (kind != EngineKind.BROWSER) CarScreenController.host()?.pushBridgePlayer()
        next.attachSurface(surface, surfaceWidth, surfaceHeight)
        next.open(source)
    }

    /** Tries the router's fallback for [kind], once. Returns false when there is none. */
    private fun stepDown(context: Context, kind: EngineKind, source: BridgeSource): Boolean {
        if (fallbackUsed) return false
        val fallback = ContentRouter.fallback(source, kind, RemoteStreamConfig.isAvailable(context))
            ?: return false
        fallbackUsed = true
        BridgeLog.i("route.fallback", "from" to kind, "to" to fallback, "url" to source.url)
        openOn(context, fallback, source)
        return true
    }

    /**
     * Reports that the live engine could not render what it was given, so the router's next
     * choice can be tried. Called by the car player screen, which is the only place that can see
     * a render failure as opposed to a load failure.
     */
    fun onEngineFailed(context: Context, error: BridgeError) {
        val source = current.source
        val kind = current.engine
        BridgeLog.w("engine.failed", "engine" to kind, "type" to error.type, "detail" to error.detail)
        if (source == null || !stepDown(context, kind, source)) {
            update { it.copy(error = error, playback = BridgePlaybackState.ERROR) }
        }
    }

    private fun engineFor(context: Context, kind: EngineKind): PlaybackEngine {
        val existing = engine
        if (existing != null && existing.kind == kind) return existing
        existing?.release()
        // The APPLICATION context, never the CarContext that was passed in.
        //
        // An engine outlives the screen that triggered it and, for the media session, outlives
        // the car session itself. Built against a CarContext, the MediaController's service
        // binding is registered on a context that is torn down when Android Auto disconnects,
        // and Media3's release then unbinds from a dead service dispatcher:
        //
        //   java.lang.IllegalArgumentException: Service not registered:
        //     androidx.media3.session.MediaControllerImplBase$SessionServiceConnection
        //
        // which killed the app on every disconnect. It also stopped this process-global object
        // holding a reference to a dead car session.
        val engineContext = context.applicationContext
        val created: PlaybackEngine = when (kind) {
            EngineKind.NATIVE -> NativePlaybackEngine(engineContext)
            EngineKind.BROWSER -> BrowserPlaybackEngine(engineContext)
            EngineKind.REMOTE_STREAM -> RemoteStreamPlaybackEngine(engineContext)
            EngineKind.UNSUPPORTED -> error("no engine for UNSUPPORTED")
        }
        created.onStateChanged = { engineState -> onEngineState(engineState) }
        engine = created
        return created
    }

    private fun onEngineState(engineState: EngineState) = post {
        val wasEnded = current.playback == BridgePlaybackState.ENDED
        update {
            it.copy(
                engine = engineState.kind,
                playback = engineState.playback,
                source = engineState.source ?: it.source,
                positionMs = engineState.positionMs,
                durationMs = engineState.durationMs,
                error = engineState.error
            )
        }
        advanceQueueIfFinished(engineState, wasEnded)
    }

    /**
     * Plays the next queued item when the native or remote player reaches the end of its own.
     *
     * Acts on the *transition* into ENDED, so one finished item moves the queue on once. A page in
     * the car browser is left to [dev.autobridge.media.MediaPlaybackService], whose one-second
     * poll already advances the queue when a page finishes; doing it here as well would skip one.
     */
    private fun advanceQueueIfFinished(engineState: EngineState, wasEnded: Boolean) {
        if (engineState.playback != BridgePlaybackState.ENDED || wasEnded) return
        if (engine is BrowserPlaybackEngine) return
        val context = appContext ?: return
        if (BrowserPlayQueue.size(context) == 0) return
        BridgeLog.i("queue.auto_next", "engine" to engineState.kind)
        next(context)
    }

    /**
     * Starts the queue on the car when nothing is playing there, so adding the first item of a
     * listening session plays it rather than leaving it to wait for a Next nobody will press.
     *
     * "Nothing is playing" is the phone's own music output being quiet, which covers every app
     * that could be sending sound to the car, not only this one's players. Returns whether it
     * started something.
     */
    fun startQueueIfIdle(context: Context): Boolean {
        if (!isConnected()) return false
        if (current.playback == BridgePlaybackState.PLAYING || current.playback == BridgePlaybackState.LOADING) return false
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        if (audio?.isMusicActive == true) return false
        val started = next(context)
        if (started) BridgeLog.i("queue.start_idle")
        return started
    }

    // ------------------------------------------------------------------------- the surface

    /** The car screen hands its surface here; whichever engine is live receives it. */
    fun attachSurface(surface: Surface?, width: Int, height: Int) {
        this.surface = surface
        surfaceWidth = width
        surfaceHeight = height
        engine?.attachSurface(surface, width, height)
        update { it.copy(hasSurface = surface != null) }
        BridgeLog.i("surface.changed", "attached" to (surface != null), "size" to "${width}x$height")
    }

    // ---------------------------------------------------------------------------- commands

    fun play() {
        BridgeLog.i("cmd.play", "engine" to current.engine)
        engine?.play()
    }

    fun pause() {
        BridgeLog.i("cmd.pause", "engine" to current.engine)
        engine?.pause()
    }

    fun togglePlayPause() {
        if (current.playback == BridgePlaybackState.PLAYING) pause() else play()
    }

    fun seekTo(positionMs: Long) {
        BridgeLog.i("cmd.seek", "positionMs" to positionMs, "engine" to current.engine)
        engine?.seekTo(positionMs)
    }

    fun seekBy(deltaMs: Long) {
        val target = (current.positionMs + deltaMs).coerceAtLeast(0L)
        seekTo(target)
    }

    /**
     * Plays the next queued page/track.
     *
     * The browser engine has its own skip, because for a page "next" means navigating the car's
     * WebView rather than handing a URL to a player; everything else goes through the ordinary
     * open path.
     */
    fun next(context: Context): Boolean {
        val live = engine
        if (live is BrowserPlaybackEngine && live.skipToNext()) {
            update { it.copy(queueSize = BrowserPlayQueue.size(context)) }
            BridgeLog.i("cmd.next", "via" to "browser queue")
            return true
        }
        val item = BrowserPlayQueue.takeNext(context)
        if (item == null) {
            BridgeLog.i("cmd.next_empty")
            return false
        }
        update { it.copy(queueSize = BrowserPlayQueue.size(context)) }
        open(context, BridgeSource(item.url, item.title, origin = BridgeSource.Origin.QUEUE))
        return true
    }

    /**
     * Restarts the current item, or steps back through it.
     *
     * There is no "previous" list: the queue is consumed as it plays, by design (see
     * [BrowserPlayQueue]). Restart is what a previous button does on every player when it is near
     * the start of a track, so it is what this does always, rather than offering a control that
     * works only sometimes.
     */
    fun previous() {
        BridgeLog.i("cmd.previous", "engine" to current.engine)
        seekTo(0L)
    }

    /** Sends a non-playback command. Only the remote stream can act on one; others ignore it. */
    fun sendControl(message: ControlMessage) {
        val live = engine
        if (live is RemoteStreamPlaybackEngine) {
            live.sendControl(message)
        } else {
            BridgeLog.w("cmd.control_ignored", "engine" to current.engine, "type" to message.type)
        }
    }

    /**
     * Types [text] into whatever the car is showing.
     *
     * The two surfaces take text by different routes and there is no common one: a remote host
     * is sent a keyboard control message, while the car browser's search field is reached
     * through [dev.autobridge.remote.TextInjectionController], the path the Mobile Remote has
     * always used. Picking between them here is what lets the phone UI offer one text box.
     *
     * @return false when nothing could receive the text.
     */
    fun sendKeyboard(context: Context, text: String, submit: Boolean): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false
        BridgeLog.i("cmd.keyboard", "chars" to clean.length, "submit" to submit, "engine" to current.engine)
        val live = engine
        if (live is RemoteStreamPlaybackEngine) {
            live.sendControl(ControlMessage.Keyboard(clean, submit))
            return true
        }
        val outcome = dev.autobridge.remote.TextInjectionController.send(
            context,
            clean,
            dev.autobridge.remote.TextInjectionController.Target.BROWSER_SEARCH,
            submit
        )
        if (!outcome.success) BridgeLog.w("cmd.keyboard_failed", "reason" to outcome.message)
        return outcome.success
    }

    /** Directional input and selection. Only a remote host can act on these. */
    fun sendNavigation(action: ControlMessage.Navigation.Action) {
        sendControl(ControlMessage.Navigation(action))
    }

    fun sendScroll(deltaX: Int, deltaY: Int) {
        sendControl(ControlMessage.Scroll(deltaX, deltaY))
    }

    fun stop(context: Context) {
        saveSnapshot(context)
        engine?.release()
        engine = null
        update {
            it.copy(
                playback = BridgePlaybackState.IDLE,
                source = null,
                engine = EngineKind.UNSUPPORTED,
                error = null
            )
        }
        BridgeLog.i("cmd.stop")
    }

    /** Pulls a fresh reading out of the live engine; the car UI calls this on its refresh tick. */
    fun refresh() {
        (engine as? BrowserPlaybackEngine)?.refresh()
        engine?.let { onEngineState(it.snapshot()) }
    }

    // ------------------------------------------------------------------------------- queue

    fun queueAdd(context: Context, source: BridgeSource): Int? {
        // Queuing what is on screen is the one case where a length is already known; everything
        // else queues an address, and the car rows label those with their source instead.
        val durationMs = if (source.url == current.source?.url) current.durationMs else 0L
        val size = BrowserPlayQueue.add(context, source.url, source.title, durationMs)
        update { it.copy(queueSize = BrowserPlayQueue.size(context)) }
        BridgeLog.i("queue.add", "url" to source.url, "size" to size)
        return size
    }

    fun queueRemove(context: Context, url: String) {
        BrowserPlayQueue.remove(context, url)
        update { it.copy(queueSize = BrowserPlayQueue.size(context)) }
        BridgeLog.i("queue.remove", "url" to url)
    }

    fun queueClear(context: Context) {
        BrowserPlayQueue.clear(context)
        update { it.copy(queueSize = 0) }
        BridgeLog.i("queue.clear")
    }

    fun queueItems(context: Context): List<BrowserPlayQueue.Item> = BrowserPlayQueue.items(context)

    // ---------------------------------------------------------------------------- handoff

    /**
     * Saves what is playing so it can be offered back later.
     *
     * Position is included when the engine knows one. For a browser page it frequently is not —
     * the read is asynchronous and a disconnect does not wait for it — so URL and title are the
     * guaranteed part of the handoff and the position is the bonus, which is exactly the floor
     * the spec sets.
     */
    fun saveSnapshot(context: Context) {
        val source = current.source ?: return
        val snapshot = BridgeStore.Snapshot(
            source = source.copy(positionMs = current.positionMs),
            engine = current.engine,
            positionMs = current.positionMs,
            savedAtMs = System.currentTimeMillis(),
            durationMs = current.durationMs
        )
        BridgeStore.setLastSession(context, snapshot)
        BridgeLog.i(
            "session.saved",
            "url" to source.url,
            "engine" to current.engine,
            "positionMs" to current.positionMs
        )
    }

    /**
     * The session to continue, from this process or the last one.
     *
     * This is the Car → Phone direction the spec asks to be *prepared for*: the snapshot is
     * written on every disconnect and stop, so the phone has somewhere to read "what was the car
     * doing" from. What is not here is automatic continuation of playback on the phone, which
     * needs a phone-side player session decision that is out of this change's scope.
     */
    fun lastSession(context: Context): BridgeStore.Snapshot? = BridgeStore.lastSession(context)

    /** Resumes [snapshot] on the car. Used by the explicit "continue" action, never automatically. */
    fun resume(context: Context, snapshot: BridgeStore.Snapshot) {
        BridgeLog.i("session.resume", "url" to snapshot.source.url, "positionMs" to snapshot.positionMs)
        open(
            context,
            snapshot.source.copy(
                positionMs = snapshot.positionMs,
                origin = BridgeSource.Origin.RESTORE
            )
        )
    }

    fun clearPending(context: Context) {
        BridgeStore.setPending(context, null)
        update { it.copy(pending = null) }
    }

    // ----------------------------------------------------------------------------- helpers

    private fun isConnected(): Boolean =
        CarScreenController.isConnected || RuntimeContextStore.context.value.connected

    private fun recordRecent(context: Context, source: BridgeSource) {
        RecentActivityStore.record(
            context,
            RecentActivityStore.Entry(
                kind = RecentActivityStore.Kind.BROWSER,
                title = source.displayTitle,
                subtitle = source.displayHost,
                data = source.url,
                origin = source.origin.recentOrigin()
            )
        )
    }

    /**
     * How a send shows up in the recents list.
     *
     * Only the origins that say something about *who asked* are carried over. QUEUE and RESTORE
     * describe the bridge continuing its own work, not a new request from anywhere, so they stay
     * null and the home dashboard's "Recently Sent" does not claim they came from the phone.
     */
    private fun BridgeSource.Origin.recentOrigin(): RecentActivityStore.Origin? = when (this) {
        BridgeSource.Origin.PHONE -> RecentActivityStore.Origin.PHONE
        BridgeSource.Origin.SHARE -> RecentActivityStore.Origin.SHARE
        BridgeSource.Origin.CAR -> RecentActivityStore.Origin.CAR
        BridgeSource.Origin.QUEUE, BridgeSource.Origin.RESTORE -> null
    }

    fun recents(context: Context, limit: Int = 10): List<RecentActivityStore.Entry> =
        RecentActivityStore.list(context, limit)

    private fun update(transform: (SessionState) -> SessionState) {
        val next = transform(_state.value)
        if (next != _state.value) _state.value = next
    }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
