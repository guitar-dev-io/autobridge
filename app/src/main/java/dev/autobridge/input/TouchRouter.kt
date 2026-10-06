package dev.autobridge.input

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManager
import dev.autobridge.R
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.mirror.MirrorCoordinator
import dev.autobridge.settings.MirrorSettings
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

    private fun withRawBackend(action: suspend (InputBackend) -> Boolean): Boolean =
        runBlocking(Dispatchers.Default) {
            if (MirrorSettings.realTouchEnabled &&
                ShizukuInputBackend.isAvailable &&
                InputCapability.REAL_TOUCH in ShizukuInputBackend.capabilities
            ) {
                action(ShizukuInputBackend)
            } else {
                false
            }
        }

    fun availableCapabilities(): Set<InputCapability> {
        if (!FeaturePolicy.app.isAvailable(Feature.TOUCH)) return emptySet()
        val backends = listOf(ShizukuInputBackend, AccessibilityInputBackend).filter { it.isAvailable }
        return backends.flatMapTo(linkedSetOf()) { it.capabilities }
    }

    /** "Shizuku" is a product name and stays as-is in every language; the other two are localized. */
    fun activeBackendLabel(context: Context): String = when {
        ShizukuInputBackend.isAvailable -> "Shizuku"
        AccessibilityInputBackend.isAvailable -> context.getString(R.string.input_backend_accessibility)
        else -> context.getString(R.string.input_backend_unavailable)
    }

    /** Describes both the privileged sink and the Android Auto source limitation. */
    fun multiTouchStatusLabel(): String = when {
        ShizukuInputBackend.isRealTouchAvailable ->
            "Real-touch injector ready; current Android Auto host still exposes no raw pointer stream"
        AccessibilityInputBackend.isAvailable && ShizukuInputBackend.isAvailable ->
            "Synthetic Accessibility pinch; raw multi-touch unavailable"
        AccessibilityInputBackend.isAvailable ->
            "Synthetic Accessibility pinch; raw pointer stream unavailable"
        ShizukuInputBackend.isPermissionGranted ->
            "Shizuku tap/swipe only; enable Accessibility for synthetic pinch"
        else ->
            "Unavailable; Android Auto raw multi-touch is not exposed"
    }

    fun rawTouchStatusLabel(): String = when {
        ShizukuInputBackend.isRealTouchAvailable && MirrorSettings.realTouchEnabled ->
            "Privileged real-touch sink enabled; host pointer stream required"
        ShizukuInputBackend.isRealTouchAvailable ->
            "Privileged real-touch sink ready; enable it only with a raw pointer source"
        else -> "Real-touch injection unavailable"
    }

    fun tap(context: Context, carX: Float, carY: Float, carWidth: Int, carHeight: Int): Boolean {
        if (!FeaturePolicy.app.isAvailable(Feature.TOUCH)) return false
        val mapped = mapPoint(context, carX, carY, carWidth, carHeight) ?: return false
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
        val gesture = PinchGeometry.forPinch(focus.x, focus.y, scaleFactor, phoneWidth, phoneHeight)
            ?: return false
        return withAvailableBackend { backend ->
            if (InputCapability.PINCH !in backend.capabilities) return@withAvailableBackend false
            backend.twoFingerGesture(gesture)
        }
    }

    /** Raw pointer entry point for a host transport that supplies pointer IDs and actions. */
    fun rawTouchDown(
        context: Context,
        pointerId: Int,
        carX: Float,
        carY: Float,
        carWidth: Int,
        carHeight: Int
    ): Boolean {
        val mapped = mapPoint(context, carX, carY, carWidth, carHeight) ?: return false
        return withRawBackend { it.touchDown(pointerId, mapped.x, mapped.y) }
    }

    fun rawTouchMove(
        context: Context,
        pointerIds: IntArray,
        carXs: FloatArray,
        carYs: FloatArray,
        carWidth: Int,
        carHeight: Int
    ): Boolean {
        if (pointerIds.isEmpty() || pointerIds.size != carXs.size || pointerIds.size != carYs.size) {
            return false
        }
        val (phoneWidth, phoneHeight) = phoneDisplaySize(context)
        val mapped = pointerIds.indices.map { index ->
            DisplayTransform.mapPoint(
                carXs[index], carYs[index], carWidth, carHeight, phoneWidth, phoneHeight
            )
        }
        if (mapped.any { it == null }) return false
        return withRawBackend {
            it.touchMove(
                pointerIds,
                mapped.map { point -> point!!.x }.toFloatArray(),
                mapped.map { point -> point!!.y }.toFloatArray()
            )
        }
    }

    fun rawTouchUp(
        context: Context,
        pointerId: Int,
        carX: Float,
        carY: Float,
        carWidth: Int,
        carHeight: Int
    ): Boolean {
        val mapped = mapPoint(context, carX, carY, carWidth, carHeight) ?: return false
        return withRawBackend { it.touchUp(pointerId, mapped.x, mapped.y) }
    }

    fun rawTouchCancel(): Boolean = withRawBackend { it.touchCancel() }

    fun longPress(context: Context, carX: Float, carY: Float, carWidth: Int, carHeight: Int): Boolean {
        if (!FeaturePolicy.app.isAvailable(Feature.TOUCH)) return false
        val mapped = mapPoint(context, carX, carY, carWidth, carHeight) ?: return false
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

    private fun mapPoint(
        context: Context,
        carX: Float,
        carY: Float,
        carWidth: Int,
        carHeight: Int
    ): CoordinateMapper.Point? {
        val (phoneWidth, phoneHeight) = phoneDisplaySize(context)
        return DisplayTransform.mapPoint(carX, carY, carWidth, carHeight, phoneWidth, phoneHeight)
    }

    private fun phoneDisplaySize(context: Context): Pair<Int, Int> {
        MirrorCoordinator.activeSourceSize?.let { source ->
            return source.width to source.height
        }
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
