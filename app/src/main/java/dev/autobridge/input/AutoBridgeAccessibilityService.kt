package dev.autobridge.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy

class AutoBridgeAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: AutoBridgeAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun tap(x: Float, y: Float): Boolean {
        if (!FeaturePolicy.app.isAvailable(Feature.TOUCH)) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long = 220): Boolean {
        if (!FeaturePolicy.app.isAvailable(Feature.TOUCH)) return false
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1L)))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun longPress(x: Float, y: Float, durationMs: Long = 500): Boolean {
        if (!FeaturePolicy.app.isAvailable(Feature.TOUCH)) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(500L)))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun twoFingerGesture(gesture: PinchGeometry.TwoFingerGesture, durationMs: Long = 200): Boolean {
        if (!FeaturePolicy.app.isAvailable(Feature.TOUCH)) return false
        val pathA = Path().apply {
            moveTo(gesture.aFromX, gesture.aFromY)
            lineTo(gesture.aToX, gesture.aToY)
        }
        val pathB = Path().apply {
            moveTo(gesture.bFromX, gesture.bFromY)
            lineTo(gesture.bToX, gesture.bToY)
        }
        val description = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(pathA, 0, durationMs.coerceAtLeast(1L)))
            .addStroke(GestureDescription.StrokeDescription(pathB, 0, durationMs.coerceAtLeast(1L)))
            .build()
        return dispatchGesture(description, null, null)
    }

    fun systemAction(action: InputBackend.SystemAction): Boolean {
        if (!FeaturePolicy.app.isAvailable(Feature.TOUCH)) return false
        val globalAction = when (action) {
            InputBackend.SystemAction.BACK -> GLOBAL_ACTION_BACK
            InputBackend.SystemAction.HOME -> GLOBAL_ACTION_HOME
            InputBackend.SystemAction.RECENTS -> GLOBAL_ACTION_RECENTS
        }
        return performGlobalAction(globalAction)
    }
}
