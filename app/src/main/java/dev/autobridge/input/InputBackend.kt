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
    /** Privileged pointer-id/action stream; not the synthetic Accessibility pinch. */
    REAL_TOUCH,
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

    /** A real pointer stream is optional and is only implemented by a privileged backend. */
    suspend fun touchDown(pointerId: Int, x: Float, y: Float): Boolean = false
    suspend fun touchMove(pointerIds: IntArray, xs: FloatArray, ys: FloatArray): Boolean = false
    suspend fun touchUp(pointerId: Int, x: Float, y: Float): Boolean = false
    suspend fun touchCancel(): Boolean = false

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
