package dev.autobridge.input

/** Actions exposed by a backend; callers can disable unsupported controls explicitly. */
enum class InputCapability {
    TAP,
    DOUBLE_TAP,
    LONG_PRESS,
    SWIPE,
    SCROLL,
    FLING,
    PINCH,
    BACK,
    HOME,
    RECENTS
}

/**
 * Common capability-aware contract for Accessibility and Shizuku injection.
 *
 * The operations are suspendable so a future remote backend can perform IPC without changing
 * callers. TouchRouter keeps the existing synchronous Android Auto callback surface and bridges
 * these short operations on a background coroutine.
 */
interface InputBackend {
    val capabilities: Set<InputCapability>
    val isAvailable: Boolean

    suspend fun tap(x: Float, y: Float): Boolean
    suspend fun swipe(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long = 220
    ): Boolean

    suspend fun longPress(x: Float, y: Float, durationMs: Long = 500): Boolean = false

    suspend fun twoFingerGesture(
        gesture: PinchGeometry.TwoFingerGesture,
        durationMs: Long = 200
    ): Boolean = false

    suspend fun back(): Boolean = false
    suspend fun home(): Boolean = false
    suspend fun recentApps(): Boolean = false

    suspend fun systemAction(action: SystemAction): Boolean = when (action) {
        SystemAction.BACK -> back()
        SystemAction.HOME -> home()
        SystemAction.RECENTS -> recentApps()
    }

    enum class SystemAction { BACK, HOME, RECENTS }
}
