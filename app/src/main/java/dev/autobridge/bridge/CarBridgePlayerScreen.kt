package dev.autobridge.bridge

import android.os.Handler
import android.os.Looper
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.R
import dev.autobridge.car.CarDisplayInfo
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.mirror.MirrorSurfaceOwnership
import dev.autobridge.mirror.ProjectionService
import dev.autobridge.safety.ParkingStateStore
import dev.autobridge.safety.SafetyEnforcement

/**
 * The car's player. Content, playback state, and the controls that matter at the wheel.
 *
 * ## What is deliberately not here
 *
 * No address bar, no menu, no settings, no tab strip, no bookmark list — nothing that asks the
 * driver to compose anything. Those exist on the phone, where there is a keyboard and no
 * distraction limit, and the car's job is to show what the phone sent. That division is the
 * point of the whole change: before it, the head unit carried a near-complete browser UI whose
 * controls were duplicated on both surfaces and whose address bar was unusable while moving.
 *
 * What is left is the set a driver can use with one glance: play/pause, ±10 s, next, and back.
 * Four actions in the strip is also the practical ceiling before a head unit starts dropping
 * them, so anything added here would silently remove something else.
 *
 * ## Loading and error states
 *
 * Both are real screens rather than a frozen surface. A [NavigationTemplate] has no text, so
 * while there is nothing to show — loading, failed, parked-gate closed — this renders a
 * [PaneTemplate] saying which of those it is, and swaps to the surface template only once there
 * is a picture to put on it. The error text comes from [BridgeErrorType]; the cause behind it
 * goes to the log and never to the screen.
 *
 * The surface itself is handed to [AutoBridgeSessionManager], which gives it to whichever engine
 * is live. This screen never talks to a player, a WebView or a decoder directly.
 */
class CarBridgePlayerScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    private companion object {
        /** Enough to keep a position readout honest without re-templating the host constantly. */
        const val REFRESH_INTERVAL_MS = 1_000L
        const val SEEK_STEP_MS = 10_000L
    }

    private val appManager = carContext.getCarService(AppManager::class.java)
    private val ticker = Handler(Looper.getMainLooper())
    private var active = false

    private val refresh = object : Runnable {
        override fun run() {
            AutoBridgeSessionManager.refresh()
            invalidate()
            if (active) ticker.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    private val parkingListener: (ParkingStateStore.State) -> Unit = {
        carContext.mainExecutor.execute {
            if (active && !allowed()) {
                AutoBridgeSessionManager.pause()
                invalidate()
            }
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                active = true
                ParkingStateStore.addListener(parkingListener)
                // Projection and this screen both want the one car surface; the mirror is the
                // one that has to yield, exactly as the native video screen does.
                ProjectionService.stop(carContext)
                MirrorSurfaceOwnership.claim(this@CarBridgePlayerScreen)
                appManager.setSurfaceCallback(this@CarBridgePlayerScreen)
                ticker.post(refresh)
                BridgeLog.i("car.player_started")
            }

            override fun onStop(owner: LifecycleOwner) {
                active = false
                ticker.removeCallbacks(refresh)
                ParkingStateStore.removeListener(parkingListener)
                if (MirrorSurfaceOwnership.isOwner(this@CarBridgePlayerScreen)) {
                    AutoBridgeSessionManager.attachSurface(null, 0, 0)
                }
                if (MirrorSurfaceOwnership.release(this@CarBridgePlayerScreen)) {
                    appManager.setSurfaceCallback(null)
                }
                // The engine is NOT released here. Leaving this screen (to the dashboard, to the
                // media card) must not stop playback — that is the complaint the session-scoped
                // browser renderer already answers, and the player behaves the same way.
                AutoBridgeSessionManager.saveSnapshot(carContext)
                BridgeLog.i("car.player_stopped")
            }
        })
    }

    private fun allowed(): Boolean =
        SafetyEnforcement.gateParked(ParkingStateStore.isParked) &&
            FeaturePolicy.app.isAvailable(Feature.VIDEO)

    // ------------------------------------------------------------------------------- surface

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this)) return
        CarDisplayInfo.record(surfaceContainer.width, surfaceContainer.height, surfaceContainer.dpi)
        AutoBridgeSessionManager.attachSurface(
            surfaceContainer.surface,
            surfaceContainer.width,
            surfaceContainer.height
        )
        invalidate()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        if (!MirrorSurfaceOwnership.isOwner(this)) return
        AutoBridgeSessionManager.attachSurface(null, 0, 0)
        invalidate()
    }

    // ------------------------------------------------------------------------------ template

    override fun onGetTemplate(): Template {
        val state = AutoBridgeSessionManager.current
        val message = statusMessage(state)
        if (message != null) return messageTemplate(state, message)

        return NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(Action.BACK)
                    .addAction(
                        control(
                            if (state.isPlaying) android.R.drawable.ic_media_pause
                            else android.R.drawable.ic_media_play,
                            enabled = allowed()
                        ) { AutoBridgeSessionManager.togglePlayPause() }
                    )
                    .addAction(
                        control(android.R.drawable.ic_media_next, enabled = state.queueSize > 0) {
                            AutoBridgeSessionManager.next(carContext)
                        }
                    )
                    .build()
            )
            .setMapActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        control(android.R.drawable.ic_media_rew) {
                            AutoBridgeSessionManager.seekBy(-SEEK_STEP_MS)
                        }
                    )
                    .addAction(
                        control(android.R.drawable.ic_media_ff) {
                            AutoBridgeSessionManager.seekBy(SEEK_STEP_MS)
                        }
                    )
                    .build()
            )
            .build()
    }

    /**
     * The one line to show instead of the picture, or null when the picture should be shown.
     *
     * Ordered by what the user can do about it: a safety gate first (park), then a refusal
     * (nothing will change until they send something else), then a wait (loading), then the
     * empty state.
     */
    private fun statusMessage(state: AutoBridgeSessionManager.SessionState): String? = when {
        !allowed() -> carContext.getString(R.string.car_video_park_to_watch)
        state.error != null -> carContext.getString(state.error.messageRes)
        state.source == null -> carContext.getString(R.string.bridge_car_nothing_playing)
        state.playback == BridgePlaybackState.LOADING ->
            carContext.getString(R.string.bridge_car_loading)
        !state.hasSurface && state.engine != EngineKind.BROWSER ->
            carContext.getString(R.string.bridge_car_loading)
        else -> null
    }

    private fun messageTemplate(
        state: AutoBridgeSessionManager.SessionState,
        message: String
    ): Template {
        val title = state.source?.displayTitle
            ?: carContext.getString(R.string.bridge_car_player_title)
        return PaneTemplate.Builder(
            Pane.Builder()
                .addRow(Row.Builder().setTitle(title).addText(message).build())
                .build()
        )
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.bridge_car_player_title))
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .build()
    }

    /**
     * An icon-only action.
     *
     * No title, deliberately: a header/action-strip entry with a custom title is rejected by a
     * real head unit's host even though the client library accepts it. [dev.autobridge.car.CarIcons]
     * records what that cost the first time.
     */
    private fun control(icon: Int, enabled: Boolean = true, action: () -> Unit): Action =
        Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .setEnabled(enabled)
            .setOnClickListener { action(); invalidate() }
            .build()
}
