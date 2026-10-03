package dev.autobridge.bridge.engine

import android.view.Surface
import dev.autobridge.bridge.BridgeSource
import dev.autobridge.bridge.EngineKind
import dev.autobridge.bridge.EngineState

/**
 * One way of getting a [BridgeSource] onto the car screen.
 *
 * Three things implement this — ExoPlayer, the car WebView, and a remote H.264 receiver — and
 * they have almost nothing in common underneath. The interface exists so that the thing choosing
 * between them ([dev.autobridge.bridge.AutoBridgeSessionManager]) and the thing drawing the
 * controls ([dev.autobridge.bridge.CarBridgePlayerScreen]) never have to know which one is
 * running. Before this, "play" meant a different call in each of three activities and the car
 * screen had a branch for each; that is the duplication this removes.
 *
 * ## Lifecycle
 *
 * `open` → (`play`/`pause`/`seekTo`)* → `release`. [open] may be called again on a live engine to
 * change source without a release in between. [release] must be idempotent: the car session is
 * torn down from several directions and double-release was the shape of the original crash.
 *
 * ## Threading
 *
 * Every method is called on the main thread. ExoPlayer's controller and the car's `AppManager`
 * both require it, and an engine that quietly hopped threads would make that impossible to
 * enforce at the boundary.
 */
interface PlaybackEngine {

    val kind: EngineKind

    /**
     * Whether this engine can open [source] at all.
     *
     * This is the engine's own opinion, asked by the manager to confirm a routing decision before
     * committing to it. [dev.autobridge.bridge.ContentRouter] decides policy ("which engine
     * should get this"); this answers capability ("could I, if asked"). They agree in the normal
     * case, and where they disagree the engine wins, because it is the one that would fail.
     */
    fun canHandle(source: BridgeSource): Boolean

    /** Loads [source], honouring its `positionMs` as far as the medium allows. */
    fun open(source: BridgeSource)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    /** Stops everything and gives up any surface. Safe to call more than once, and when idle. */
    fun release()

    /** Where this engine stands right now, for the car and phone UIs to render. */
    fun snapshot(): EngineState

    /**
     * Attaches the car's output surface, or detaches it with null.
     *
     * Only the engines that draw pixels themselves do anything here — the browser engine renders
     * through [dev.autobridge.browser.CarBrowserRuntime], which owns its own surface and has
     * since before this interface existed. The default is therefore "nothing", so a surface
     * engine is the exception it should be rather than an empty override in every implementation.
     */
    fun attachSurface(surface: Surface?, width: Int, height: Int) = Unit

    /** Invoked on the main thread whenever [snapshot] would return something new. */
    var onStateChanged: ((EngineState) -> Unit)?
}
