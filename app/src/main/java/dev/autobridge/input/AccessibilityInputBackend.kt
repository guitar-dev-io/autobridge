package dev.autobridge.input

import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy

object AccessibilityInputBackend : InputBackend {
    override val capabilities: Set<InputCapability> = setOf(
        InputCapability.TAP,
        InputCapability.LONG_PRESS,
        InputCapability.SWIPE,
        InputCapability.SCROLL,
        InputCapability.FLING,
        InputCapability.PINCH,
        InputCapability.BACK,
        InputCapability.HOME,
        InputCapability.RECENTS
    )

    override val isAvailable: Boolean
        get() = AutoBridgeAccessibilityService.instance != null &&
            FeaturePolicy.app.isAvailable(Feature.TOUCH)

    override suspend fun tap(x: Float, y: Float): Boolean =
        AutoBridgeAccessibilityService.instance?.tap(x, y) ?: false

    override suspend fun swipe(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long
    ): Boolean = AutoBridgeAccessibilityService.instance?.swipe(
        fromX,
        fromY,
        toX,
        toY,
        durationMs
    ) ?: false

    override suspend fun longPress(x: Float, y: Float, durationMs: Long): Boolean =
        AutoBridgeAccessibilityService.instance?.longPress(x, y, durationMs) ?: false

    override suspend fun twoFingerGesture(
        gesture: PinchGeometry.TwoFingerGesture,
        durationMs: Long
    ): Boolean = AutoBridgeAccessibilityService.instance?.twoFingerGesture(gesture, durationMs) ?: false

    override suspend fun back(): Boolean =
        AutoBridgeAccessibilityService.instance?.systemAction(InputBackend.SystemAction.BACK) ?: false

    override suspend fun home(): Boolean =
        AutoBridgeAccessibilityService.instance?.systemAction(InputBackend.SystemAction.HOME) ?: false

    override suspend fun recentApps(): Boolean =
        AutoBridgeAccessibilityService.instance?.systemAction(InputBackend.SystemAction.RECENTS) ?: false

    override suspend fun systemAction(action: InputBackend.SystemAction): Boolean =
        AutoBridgeAccessibilityService.instance?.systemAction(action) ?: false
}
