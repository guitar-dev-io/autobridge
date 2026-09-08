package dev.autobridge.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import android.view.Surface

/**
 * Tracks the phone's own (DEFAULT_DISPLAY) rotation while a mirror session is active.
 *
 * This does not change mapping correctness: [dev.autobridge.input.TouchRouter] already re-queries
 * the live, rotation-correct phone size on every tap, and MediaProjection's AUTO_MIRROR already
 * re-scales the mirrored image transparently at the OS level on rotation. This exists to make
 * rotation state observable — for status display, and as a hook future per-app force-landscape
 * profiles (V0.4) or FPS/latency diagnostics can key off.
 */
object OrientationMonitor {
    private const val TAG = "AutoBridgeOrientation"

    @Volatile
    var currentRotation: Int = Surface.ROTATION_0
        private set

    private var displayManager: DisplayManager? = null

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val rotation = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: return
            if (rotation != currentRotation) {
                Log.i(TAG, "Phone rotation changed: ${label(currentRotation)} -> ${label(rotation)}")
                currentRotation = rotation
            }
        }
    }

    fun start(context: Context) {
        if (displayManager != null) return
        val manager = context.getSystemService(DisplayManager::class.java) ?: return
        displayManager = manager
        currentRotation = manager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
        manager.registerDisplayListener(listener, null)
    }

    fun stop() {
        displayManager?.unregisterDisplayListener(listener)
        displayManager = null
    }

    fun label(rotation: Int = currentRotation): String = when (rotation) {
        Surface.ROTATION_0 -> "0°"
        Surface.ROTATION_90 -> "90°"
        Surface.ROTATION_180 -> "180°"
        Surface.ROTATION_270 -> "270°"
        else -> "unknown"
    }
}
