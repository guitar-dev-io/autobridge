package dev.autobridge.duoscreen

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.autobridge.logging.StructuredLog

/**
 * Holds the one live Duo Screen session for the process, so it outlives the [dev.autobridge.duoscreen.car.DuoScreenScreen] that
 * happens to be showing it.
 *
 * Without this, re-opening Duo Screen from the car launcher built a new Screen, a new
 * [DuoScreenController], new VirtualDisplays and a fresh launch of every pane app — the driver's
 * panes started over every time they came back to it. The controller is the session; the Screen is
 * only the thing currently drawing it.
 *
 * A session nobody comes back to still has to end, or its displays and the apps running in them
 * would sit there for the life of the process. So leaving arms [KEEP_ALIVE_MS], and coming back
 * within it disarms the timer and resumes; past it, the session is torn down and the next entry
 * starts fresh.
 */
object DuoScreenHost {
    private const val TAG = "AutoBridgeDuoHost"

    /**
     * How long a detached session is kept. Long enough to cover a look at the navigation app or a
     * call and back; short enough that a session genuinely finished with does not hold onto its
     * displays for the rest of the drive.
     */
    const val KEEP_ALIVE_MS = 120_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var controller: DuoScreenController? = null

    private val expire = Runnable {
        StructuredLog.i(TAG, "Nothing came back within ${KEEP_ALIVE_MS}ms; ending the session")
        release()
    }

    /** The live session, created on first use. Cancels any pending teardown. */
    fun controller(context: Context): DuoScreenController {
        mainHandler.removeCallbacks(expire)
        return controller ?: DuoScreenController(context.applicationContext).also {
            controller = it
            StructuredLog.i(TAG, "Duo Screen session created")
        }
    }

    /**
     * The phone-side settings changed. Pushes them onto the live session and reports whether one
     * took them, so the settings screen can say whether the car display has already changed or
     * whether the choice is waiting for the next connection.
     *
     * The settings screen and the car app service share this process, so this is a direct call —
     * there is no session to find and nothing to broadcast.
     */
    fun onSettingsChanged(): Boolean = controller?.applyStoredSettings() ?: false

    /**
     * "Reset the arrangement" with a session running: the stored preset has not changed, so
     * [onSettingsChanged] would see nothing to do — the panes have to be put back on it explicitly.
     */
    fun onLayoutReset(): Boolean = controller?.reapplyPreset() ?: false

    /** The Screen showing the session has gone; keep it for [KEEP_ALIVE_MS] in case it comes back. */
    fun onScreenGone() {
        val active = controller ?: return
        active.detach()
        mainHandler.removeCallbacks(expire)
        mainHandler.postDelayed(expire, KEEP_ALIVE_MS)
    }

    /**
     * Ends the session now: every pane display goes, and the apps in them with it. Returns whether
     * there was a session to end, so a caller that offers this as a button can say whether it did
     * anything — "remove the panes" with nothing running looks identical otherwise.
     *
     * Safe to call before the Screen showing the session is destroyed: [onScreenGone] then finds
     * nothing and arms no keep-alive timer, which is exactly what an explicit "end it" wants.
     */
    fun release(): Boolean {
        mainHandler.removeCallbacks(expire)
        val active = controller ?: return false
        active.stop()
        controller = null
        StructuredLog.i(TAG, "Duo Screen session released")
        return true
    }
}
