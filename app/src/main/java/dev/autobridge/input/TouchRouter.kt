package dev.autobridge.input

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManager
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

object TouchRouter {
    /** Synchronous Android Auto callback bridge for the suspendable backend contract. */
    private fun withAvailableBackend(action: suspend (InputBackend) -> Boolean): Boolean =
        runBlocking(Dispatchers.Default) {
            if (ShizukuInputBackend.isAvailable && action(ShizukuInputBackend)) return@runBlocking true
            if (AccessibilityInputBackend.isAvailable && action(AccessibilityInputBackend)) return@runBlocking true
            false
        }

    fun availableCapabilities(): Set<InputCapability> {
        if (!FeaturePolicy.app.isAvailable(Feature.TOUCH)) return emptySet()
        val backends = listOf(ShizukuInputBackend, AccessibilityInputBackend).filter { it.isAvailable }
        return backends.flatMapTo(linkedSetOf()) { it.capabilities }
    }

    fun activeBackendLabel(): String = when {
        ShizukuInputBackend.isAvailable -> "Shizuku"
        AccessibilityInputBackend.isAvailable -> "Accessibility"
        else -> "Unavailable"
    }

    fun tap(context: Context, carX: Float, carY: Float, carWidth: Int, carHeight: Int): Boolean {
        if (!FeaturePolicy.app.isAvailable(Feature.TOUCH)) return false
        val (phoneWidth, phoneHeight) = phoneDisplaySize(context)
        val mapped = DisplayTransform.mapPoint(
            carX, carY, carWidth, carHeight, phoneWidth, phoneHeight
        ) ?: return false
        return withAvailableBackend { it.tap(mapped.x, mapped.y) }
    }

    fun pinch(
        context: Context,
        carFocusX: Float,
        carFocusY: Float,
        carWidth: Int,
        carHeight: Int,
        scaleFactor: Float
    ): Boolean {
        if (!FeaturePolicy.app.isAvailable(Feature.TOUCH)) return false
        val (phoneWidth, phoneHeight) = phoneDisplaySize(context)
        val focus = DisplayTransform.mapPoint(
            carFocusX, carFocusY, carWidth, carHeight, phoneWidth, phoneHeight
        ) ?: return false
        val gesture = PinchGeometry.forPinch(focus.x, focus.y, scaleFactor, phoneWidth, phoneHeight) ?: return false
        return withAvailableBackend { backend ->
            if (InputCapability.PINCH !in backend.capabilities) return@withAvailableBackend false
            backend.twoFingerGesture(gesture)
        }
    }

    fun longPress(context: Context, carX: Float, carY: Float, carWidth: Int, carHeight: Int): Boolean {
        if (!FeaturePolicy.app.isAvailable(Feature.TOUCH)) return false
        val (phoneWidth, phoneHeight) = phoneDisplaySize(context)
        val mapped = DisplayTransform.mapPoint(carX, carY, carWidth, carHeight, phoneWidth, phoneHeight)
            ?: return false
        return withAvailableBackend { backend ->
            if (InputCapability.LONG_PRESS !in backend.capabilities) return@withAvailableBackend false
            backend.longPress(mapped.x, mapped.y)
        }
    }

    fun systemAction(action: InputBackend.SystemAction): Boolean {
        if (!FeaturePolicy.app.isAvailable(Feature.TOUCH)) return false
        val capability = when (action) {
            InputBackend.SystemAction.BACK -> InputCapability.BACK
            InputBackend.SystemAction.HOME -> InputCapability.HOME
            InputBackend.SystemAction.RECENTS -> InputCapability.RECENTS
        }
        return withAvailableBackend { backend ->
            if (capability !in backend.capabilities) return@withAvailableBackend false
            backend.systemAction(action)
        }
    }

    fun back() = systemAction(InputBackend.SystemAction.BACK)
    fun home() = systemAction(InputBackend.SystemAction.HOME)
    fun recents() = systemAction(InputBackend.SystemAction.RECENTS)

    fun scroll(context: Context, distanceX: Float, distanceY: Float): Boolean {
        if (!FeaturePolicy.app.isAvailable(Feature.TOUCH)) return false
        val (width, height) = phoneDisplaySize(context)
        val startX = width / 2f
        val startY = height / 2f
        val factor = 1.25f
        val endX = (startX - distanceX * factor).coerceIn(0f, width.toFloat())
        val endY = (startY - distanceY * factor).coerceIn(0f, height.toFloat())
        return withAvailableBackend { backend ->
            if (InputCapability.SCROLL !in backend.capabilities) return@withAvailableBackend false
            backend.swipe(startX, startY, endX, endY)
        }
    }

    private fun phoneDisplaySize(context: Context): Pair<Int, Int> {
        val display = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)

        if (display != null && android.os.Build.VERSION.SDK_INT >= 30) {
            val bounds = context.createDisplayContext(display)
                .getSystemService(WindowManager::class.java)
                .currentWindowMetrics
                .bounds
            return bounds.width() to bounds.height()
        }

        if (display != null) {
            @Suppress("DEPRECATION")
            val point = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(point)
            return point.x to point.y
        }

        @Suppress("DEPRECATION")
        val metrics = context.resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }
}
