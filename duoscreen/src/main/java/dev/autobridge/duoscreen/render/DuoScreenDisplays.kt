package dev.autobridge.duoscreen.render

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import android.view.Surface
import dev.autobridge.duoscreen.system.DuoScreenPrivilegedOps
import java.util.concurrent.ConcurrentHashMap

/**
 * One VirtualDisplay per pane. Two ways to get it, in order of preference.
 *
 * **Trusted, own display group, over Shizuku** ([DuoScreenPrivilegedOps.createTrustedVirtualDisplay]).
 * An ordinary app's VirtualDisplay lives in the *default display group*, whose power state follows
 * display 0 (the phone panel). So when the hardware POWER button puts the device into real sleep,
 * the system stops composing the pane and the car/DHU surface goes black — the bug this guards
 * against. A trusted display in its own display group ([FLAG_TRUSTED] + [FLAG_OWN_DISPLAY_GROUP])
 * does not follow display 0, so the car pane keeps rendering while the phone panel is dark. The
 * trusted flag needs `ADD_TRUSTED_DISPLAY`, refused to an app process but held by the shell UID
 * Shizuku lends, so this path is only taken when Shizuku is granted.
 *
 * **Untrusted, this app's own [DisplayManager]** (the fallback). Needs no permission at all, and a
 * launch arriving with shell privileges still lands on it and shell-injected touch reaches it — so
 * without Shizuku the session behaves exactly as it did before this fix (works while the phone is
 * awake; dies on a real power-button sleep). The fallback is also taken when the trusted creation
 * fails on an OEM/ROM that rejects it, so the app is never worse off than the untrusted path.
 */
object DuoScreenDisplays {
    private const val TAG = "AutoBridgeDuoDisplay"

    /** Documented AOSP bit values; the constants are not all in the public SDK. */
    private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
    private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
    private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    private const val FLAG_TRUSTED = 1 shl 10
    private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11

    /**
     * The untrusted fallback flags — what an app may set with no permission.
     *
     * [FLAG_DESTROY_CONTENT_ON_REMOVAL]: without it, releasing a pane's display moves the app in it
     * onto the phone screen and keeps its task alive, so the next session's launch brought that
     * same task back — the calculator still showing the last sum, Settings still on the last page.
     * A pane's app now ends with its pane and starts clean next time.
     */
    private const val FLAGS = FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or FLAG_DESTROY_CONTENT_ON_REMOVAL

    /**
     * The trusted flags for the Shizuku path. [FLAG_OWN_DISPLAY_GROUP] is the decisive one for
     * surviving the phone's sleep; [FLAG_TRUSTED] is what the system requires before it will honour
     * the own-group request from a privileged caller. The touch/own-content bits are kept so the
     * trusted display behaves like the untrusted one in every other respect.
     */
    const val TRUSTED_FLAGS = FLAGS or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP

    /**
     * A pane's display and the size it is actually running at, which is not always its rect's.
     *
     * A trusted pane has no app-side [display] handle: it was created by the shell UID, so its
     * [trustedDisplayId] (>= 0) and the [ops] that minted it are what resize/setSurface/release
     * route through. An untrusted pane carries the ordinary [display] and [trustedDisplayId] = -1.
     */
    private class PaneDisplay(
        val display: VirtualDisplay?,
        var width: Int,
        var height: Int,
        var dpi: Int,
        val trustedDisplayId: Int = -1,
        val ops: DuoScreenPrivilegedOps? = null,
    ) {
        val isTrusted: Boolean get() = trustedDisplayId >= 0
        val displayId: Int
            get() = if (isTrusted) trustedDisplayId else display?.display?.displayId ?: -1
    }

    /** The size a pane's display is actually running at. */
    data class PaneSize(val width: Int, val height: Int)

    private val displays = ConcurrentHashMap<Int, PaneDisplay>()

    /** Returns the new display's id for [paneId], or -1. Replaces any display that pane already had. */
    fun create(
        context: Context,
        paneId: Int,
        surface: Surface,
        width: Int,
        height: Int,
        dpi: Int,
        ops: DuoScreenPrivilegedOps,
    ): Int {
        release(paneId)
        val name = "AutoBridgeDuoPane$paneId"

        // Prefer the trusted, own-display-group path so the pane survives the phone's power button.
        if (ops.isAvailable) {
            val trustedId = ops.createTrustedVirtualDisplay(name, width, height, dpi, surface, TRUSTED_FLAGS)
            if (trustedId >= 0) {
                displays[paneId] = PaneDisplay(null, width, height, dpi, trustedDisplayId = trustedId, ops = ops)
                Log.i(TAG, "Pane $paneId -> trusted display $trustedId (${width}x$height @ ${dpi}dpi)")
                return trustedId
            }
            Log.w(TAG, "Pane $paneId trusted display unavailable; falling back to untrusted")
        }

        val manager = context.getSystemService(DisplayManager::class.java) ?: return -1
        val display = runCatching {
            manager.createVirtualDisplay(name, width, height, dpi, surface, FLAGS)
                ?: error("createVirtualDisplay returned null")
        }.getOrElse { error ->
            Log.w(TAG, "Pane $paneId display creation failed (${width}x$height @ ${dpi}dpi)", error)
            return -1
        }
        displays[paneId] = PaneDisplay(display, width, height, dpi)
        val displayId = display.display.displayId
        Log.i(TAG, "Pane $paneId -> untrusted display $displayId (${width}x$height @ ${dpi}dpi)")
        return displayId
    }

    fun displayId(paneId: Int): Int = displays[paneId]?.displayId ?: -1

    /**
     * The size [paneId]'s display is running at, which is the coordinate space a touch injected
     * into it has to be in. It is not the pane's rect: the rect moves with every frame of a drag
     * while the display is only resized once the gesture settles, and a resize can also fail.
     */
    fun size(paneId: Int): PaneSize? = displays[paneId]?.let { PaneSize(it.width, it.height) }

    /**
     * Resizes [paneId]'s display, or does nothing and reports success when it already has that
     * geometry. The skip matters: a pane that was only *moved* keeps its size, and resizing a
     * VirtualDisplay to the size it already has still puts the hosted app through a configuration
     * change — a visible blink, once per drag, for no change at all.
     *
     * A trusted pane is resized over the same shell seam that created it; an untrusted one through
     * its own [VirtualDisplay] handle.
     */
    fun resize(paneId: Int, width: Int, height: Int, dpi: Int): Boolean {
        val pane = displays[paneId] ?: return false
        if (pane.width == width && pane.height == height && pane.dpi == dpi) return true
        val resized = if (pane.isTrusted) {
            pane.ops?.resizeTrustedVirtualDisplay(pane.trustedDisplayId, width, height, dpi) ?: false
        } else {
            runCatching { pane.display?.resize(width, height, dpi) }
                .onFailure { Log.w(TAG, "Pane $paneId resize failed", it) }
                .isSuccess
        }
        if (resized) {
            pane.width = width
            pane.height = height
            pane.dpi = dpi
        } else {
            Log.w(TAG, "Pane $paneId resize failed (${if (pane.isTrusted) "trusted" else "untrusted"})")
        }
        return resized
    }

    /** True when [resize] would actually change something; lets a caller skip the work around it. */
    fun needsResize(paneId: Int, width: Int, height: Int, dpi: Int): Boolean {
        val pane = displays[paneId] ?: return false
        return pane.width != width || pane.height != height || pane.dpi != dpi
    }

    fun setSurface(paneId: Int, surface: Surface?): Boolean {
        val pane = displays[paneId] ?: return false
        return if (pane.isTrusted) {
            pane.ops?.setTrustedVirtualDisplaySurface(pane.trustedDisplayId, surface) ?: false
        } else {
            runCatching { pane.display?.surface = surface }
                .onFailure { Log.w(TAG, "Pane $paneId setSurface failed", it) }
                .isSuccess
        }
    }

    fun release(paneId: Int) {
        val pane = displays.remove(paneId) ?: return
        if (pane.isTrusted) {
            runCatching { pane.ops?.releaseTrustedVirtualDisplay(pane.trustedDisplayId) }
                .onFailure { Log.w(TAG, "Pane $paneId trusted release failed", it) }
        } else {
            runCatching { pane.display?.release() }
                .onFailure { Log.w(TAG, "Pane $paneId release failed", it) }
        }
    }

    fun releaseAll() {
        displays.keys.toList().forEach(::release)
    }
}
