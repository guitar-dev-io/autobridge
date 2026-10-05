package dev.autobridge.duoscreen.render

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import android.view.Surface
import java.util.concurrent.ConcurrentHashMap

/**
 * One VirtualDisplay per pane, created by this app with no permission at all.
 *
 * Deliberately *private + own-content and not trusted*: VIRTUAL_DISPLAY_FLAG_TRUSTED needs
 * ADD_TRUSTED_DISPLAY and is refused to an app process ("Requires ADD_TRUSTED_DISPLAY permission to
 * create a trusted virtual display", confirmed on-device), and it turned out not to be needed —
 * a launch arriving with shell privileges lands on an untrusted display and stays there, and
 * shell-injected touch reaches it. So the privileged half shrinks to [dev.autobridge.duoscreen.system.DuoScreenPrivilegedOps] and
 * display lifecycle stays ordinary app code.
 */
object DuoScreenDisplays {
    private const val TAG = "AutoBridgeDuoDisplay"

    /** Documented AOSP bit values; the constants are not all in the public SDK. */
    private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
    private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
    private const val FLAGS = FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH

    /** A pane's display and the size it is actually running at, which is not always its rect's. */
    private class PaneDisplay(val display: VirtualDisplay, var width: Int, var height: Int, var dpi: Int)

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
        dpi: Int
    ): Int {
        release(paneId)
        val manager = context.getSystemService(DisplayManager::class.java) ?: return -1
        val display = runCatching {
            manager.createVirtualDisplay("AutoBridgeDuoPane$paneId", width, height, dpi, surface, FLAGS)
                ?: error("createVirtualDisplay returned null")
        }.getOrElse { error ->
            Log.w(TAG, "Pane $paneId display creation failed (${width}x$height @ ${dpi}dpi)", error)
            return -1
        }
        displays[paneId] = PaneDisplay(display, width, height, dpi)
        val displayId = display.display.displayId
        Log.i(TAG, "Pane $paneId -> display $displayId (${width}x$height @ ${dpi}dpi)")
        return displayId
    }

    fun displayId(paneId: Int): Int = displays[paneId]?.display?.display?.displayId ?: -1

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
     */
    fun resize(paneId: Int, width: Int, height: Int, dpi: Int): Boolean {
        val pane = displays[paneId] ?: return false
        if (pane.width == width && pane.height == height && pane.dpi == dpi) return true
        return runCatching {
            pane.display.resize(width, height, dpi)
            pane.width = width
            pane.height = height
            pane.dpi = dpi
        }.onFailure { Log.w(TAG, "Pane $paneId resize failed", it) }.isSuccess
    }

    /** True when [resize] would actually change something; lets a caller skip the work around it. */
    fun needsResize(paneId: Int, width: Int, height: Int, dpi: Int): Boolean {
        val pane = displays[paneId] ?: return false
        return pane.width != width || pane.height != height || pane.dpi != dpi
    }

    fun setSurface(paneId: Int, surface: Surface?): Boolean {
        val pane = displays[paneId] ?: return false
        return runCatching { pane.display.surface = surface }
            .onFailure { Log.w(TAG, "Pane $paneId setSurface failed", it) }
            .isSuccess
    }

    fun release(paneId: Int) {
        val pane = displays.remove(paneId) ?: return
        runCatching { pane.display.release() }.onFailure { Log.w(TAG, "Pane $paneId release failed", it) }
    }

    fun releaseAll() {
        displays.keys.toList().forEach(::release)
    }
}
