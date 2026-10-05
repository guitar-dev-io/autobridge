package dev.autobridge.display

import android.content.Context
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.core.content.edit
import dev.autobridge.logging.StructuredLog

/**
 * Locks a drawing canvas on the Android Auto host surface, preferring the hardware one but never
 * at the cost of the process.
 *
 * ## Why this is not just `lockHardwareCanvas()`
 *
 * [Surface.lockHardwareCanvas] builds a `HardwareRenderer` for the surface and draws it on the
 * shared RenderThread. On some devices - reproduced on HyperOS 3 / MediaTek, Android 16, against
 * the Desktop Head Unit - the renderer cannot create an EGL window surface for the host's surface,
 * and the failure does not come back as an exception. It surfaces one frame later inside
 * `SkiaOpenGLPipeline::getFrame` as
 *
 * ```
 * F HWUI: drawRenderNode called on a context with no surface!
 * F libc: Fatal signal 6 (SIGABRT) in tid NNN (RenderThread)
 * ```
 *
 * which aborts the whole process. The car host restarts the app, the app draws its home again, and
 * it aborts again: a loop that no `try`/`catch` around the lock can break, because by the time the
 * assert fires the main thread has already returned from `unlockCanvasAndPost`.
 *
 * ## What this does instead
 *
 * The first hardware frame on a device is a canary. "I am about to try hardware" is written to disk
 * *before* the frame, and replaced with "hardware works" only once the process has survived
 * [CONFIRM_DELAY_MS] past it. The delay is the whole point: `unlockCanvasAndPost` returns as soon as
 * the frame is handed to the RenderThread, long before the GL draw that aborts, so "the call came
 * back" proves nothing and the first version of this canary disarmed itself 25ms before the crash
 * it was watching for.
 *
 * A process that starts and finds the flag still saying "about to try" is a process whose
 * predecessor died in that frame, so this device is marked as software-only for good and the loop
 * ends after exactly one crash.
 *
 * Devices where the hardware canvas works - which is most of them, including every head unit this
 * has run on in a car - pay one synchronous preference write, once, ever.
 */
object CarSurfaceCanvas {
    private const val PREFS_NAME = "autobridge_display"
    // v2: the first key was written by a canary that disarmed too early, so its stored verdict
    // cannot be trusted and every device re-tests once under the corrected rule.
    private const val KEY_MODE = "hw_canvas_mode_v2"

    /**
     * How long the process must outlive a hardware frame before that frame counts as proof.
     *
     * Must comfortably exceed the abort latency: `unlockCanvasAndPost` returns as soon as the frame
     * is handed to the RenderThread, and the GL draw that aborts lands later. A HyperOS 3 / MediaTek
     * head unit was observed aborting ~2s after the frame, so a 2s window could promote to [WORKS]
     * one tick before the crash. Four seconds leaves that race no room.
     */
    private const val CONFIRM_DELAY_MS = 4_000L

    private const val TRYING = "trying"
    private const val WORKS = "works"
    private const val BROKEN = "broken"

    /** Resolved once per process; the disk value only changes on the first frame or after a crash. */
    @Volatile
    private var cached: String? = null

    /** One canary per process, however many frames it draws. */
    @Volatile
    private var armed = false

    private val main = Handler(Looper.getMainLooper())

    /**
     * Draws one frame onto [surface] and posts it.
     *
     * @return false when no canvas could be locked at all, which is the normal outcome when the
     *   host has taken the surface back; the caller simply skips the frame.
     */
    fun draw(context: Context, surface: Surface, block: (Canvas) -> Unit): Boolean {
        val mode = mode(context)
        // Which branch produced the canvas, recorded here rather than read back from
        // Canvas.isHardwareAccelerated: the canary has to key off what was actually called.
        var hardware = false
        val canvas = (if (mode == BROKEN) null
        else runCatching { surface.lockHardwareCanvas() }.getOrNull()?.also { hardware = true })
            ?: runCatching { surface.lockCanvas(null) }.getOrNull()
            ?: return false

        // Armed before the frame, because the frame is what may never return. Re-armed on every
        // process start even when the stored verdict is already WORKS: the abort is not purely a
        // device property - a device marked WORKS can still abort on a later run (a wrongly promoted
        // verdict from the old 2s window, a firmware update, a host that tears the surface down mid
        // frame), and without a re-test that device loops forever with the canary disarmed. So the
        // first hardware frame of *this* process writes TRYING regardless of the stored value; if
        // the process dies in the confirm window the next launch reads TRYING and demotes to BROKEN,
        // which is what finally breaks the restart loop. A genuinely-working device simply rewrites
        // WORKS once per launch and is otherwise untouched.
        if (hardware && mode != BROKEN && !armed) {
            armed = true
            val application = context.applicationContext
            StructuredLog.i(TAG, "first hardware frame this run (stored=$mode) - arming the canary")
            setMode(application, TRYING)
            main.postDelayed({
                StructuredLog.i(TAG, "hardware canvas survived; keeping it")
                setMode(application, WORKS)
            }, CONFIRM_DELAY_MS)
        }
        try {
            block(canvas)
        } finally {
            runCatching { surface.unlockCanvasAndPost(canvas) }
        }
        return true
    }

    /** True when this device has been found to abort on a hardware canvas. For diagnostics rows. */
    fun isSoftwareOnly(context: Context): Boolean = mode(context) == BROKEN

    private fun mode(context: Context): String {
        cached?.let { return it }
        val stored = prefs(context).getString(KEY_MODE, null)
        // Still "trying" means the process that armed it never came back from that frame.
        val resolved = if (stored == TRYING) {
            StructuredLog.w(TAG, "hardware canvas aborted on the previous run; software from now on")
            setMode(context, BROKEN)
            BROKEN
        } else {
            stored ?: ""
        }
        cached = resolved
        return resolved
    }

    private fun setMode(context: Context, mode: String) {
        cached = mode
        // commit, not apply: the canary is worthless if it is still in a queue when the frame
        // takes the process down with it.
        prefs(context).edit(commit = true) { putString(KEY_MODE, mode) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val TAG = "CarSurface"
}
