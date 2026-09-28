package dev.autobridge.input

import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import java.lang.reflect.Method
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

/** Pure command-vector builder kept testable without a Shizuku process. */
object ShizukuInputCommand {
    fun args(action: String, vararg values: String): List<String> =
        listOf(action) + values.toList()
}

/** Runs one bounded shell command so a hung `input` process cannot block Binder forever. */
object ShizukuCommandRunner {
    const val DEFAULT_TIMEOUT_MS = 1_500L
    private const val TAG = "AutoBridgeShizukuCmd"

    fun run(args: List<String>, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        if (args.isEmpty() || timeoutMs <= 0L) return false
        val process = runCatching {
            ProcessBuilder("input", *args.toTypedArray())
                .redirectErrorStream(true)
                .start()
        }.getOrElse {
            Log.w(TAG, "Could not start input command", it)
            return false
        }
        val completed = runCatching { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }
            .getOrElse {
                Log.w(TAG, "Could not wait for input command", it)
                false
            }
        if (!completed) {
            process.destroyForcibly()
            Log.w(TAG, "Timed out input command args=$args")
            return false
        }
        val exitCode = process.exitValue()
        if (exitCode != 0) Log.w(TAG, "input command failed exit=$exitCode args=$args")
        return exitCode == 0
    }
}

/**
 * Hidden display-power bridge used only from the Shizuku shell-UID process. It intentionally uses
 * panel power mode rather than PowerManager.goToSleep()/DeviceAdmin.lockNow(), so the device is
 * not locked and the projection source can continue composing. Android releases move this API
 * between SurfaceControl and DisplayControl, so capability probing is mandatory and failure is
 * reported instead of being treated as support.
 */
object ShizukuDisplayPowerController {
    private const val TAG = "AutoBridgeDisplayPower"
    private const val POWER_MODE_OFF = 0
    private const val POWER_MODE_NORMAL = 2
    private val candidateClasses = listOf("android.view.DisplayControl", "android.view.SurfaceControl")

    private data class ResolvedApi(val setter: Method, val token: IBinder)

    fun isAvailable(): Boolean = resolve() != null

    fun setDisplayPower(on: Boolean): Boolean {
        val api = resolve() ?: return false
        return runCatching {
            api.setter.invoke(null, api.token, if (on) POWER_MODE_NORMAL else POWER_MODE_OFF)
            true
        }.onFailure { Log.w(TAG, "setDisplayPowerMode failed", it) }.getOrDefault(false)
    }

    private fun resolve(): ResolvedApi? {
        for (className in candidateClasses) {
            val resolved = runCatching {
                val clazz = Class.forName(className)
                val setter = clazz.getMethod(
                    "setDisplayPowerMode",
                    IBinder::class.java,
                    Int::class.javaPrimitiveType!!
                )
                val token = findDefaultDisplayToken(clazz) ?: return@runCatching null
                ResolvedApi(setter, token)
            }.getOrNull()
            if (resolved != null) return resolved
        }
        return null
    }

    private fun findDefaultDisplayToken(clazz: Class<*>): IBinder? {
        runCatching {
            clazz.getMethod("getInternalDisplayToken").invoke(null) as? IBinder
        }.getOrNull()?.let { return it }

        runCatching {
            clazz.getMethod("getBuiltInDisplay", Int::class.javaPrimitiveType!!)
                .invoke(null, 0) as? IBinder
        }.getOrNull()?.let { return it }

        val ids = runCatching {
            clazz.getMethod("getPhysicalDisplayIds").invoke(null) as? LongArray
        }.getOrNull()
        val tokenMethod = runCatching {
            clazz.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType!!)
        }.getOrNull()
        if (ids != null && tokenMethod != null) {
            ids.toList().firstNotNullOfOrNull { id ->
                runCatching { tokenMethod.invoke(null, id) as? IBinder }.getOrNull()
            }?.let { return it }
        }
        return null
    }
}

/**
 * Real pointer-id/action injector for the privileged Shizuku process. Android Auto currently does
 * not deliver the source stream, so this is a sink for a future raw host transport rather than a
 * fake conversion of onScale. InputManager is reached reflectively because it is a hidden API;
 * capability is exposed only when the probe and first injection path are available.
 */
object ShizukuRealTouchController {
    private const val TAG = "AutoBridgeRealTouch"
    private const val MAX_POINTERS = 10
    private const val MAX_COORDINATE = 10_000
    private const val INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH = 2
    private const val ACTION_POINTER_INDEX_SHIFT = 8

    private data class Pointer(var x: Int, var y: Int)
    private data class InputApi(val manager: Any, val inject: Method)

    private val lock = Any()
    private val pointers = LinkedHashMap<Int, Pointer>()
    private var downTimeMs = 0L

    fun isAvailable(): Boolean = resolveInputApi() != null

    fun touchDown(pointerId: Int, x: Int, y: Int): Boolean = synchronized(lock) {
        if (!validPointer(pointerId, x, y) || pointers.containsKey(pointerId) ||
            pointers.size >= MAX_POINTERS
        ) return false

        val wasEmpty = pointers.isEmpty()
        if (wasEmpty) downTimeMs = SystemClock.uptimeMillis()
        pointers[pointerId] = Pointer(x, y)
        val index = pointers.keys.indexOf(pointerId)
        val action = if (wasEmpty) {
            MotionEvent.ACTION_DOWN
        } else {
            MotionEvent.ACTION_POINTER_DOWN or (index shl ACTION_POINTER_INDEX_SHIFT)
        }
        if (inject(action)) return true
        pointers.remove(pointerId)
        if (wasEmpty) downTimeMs = 0L
        false
    }

    fun touchMove(pointerIds: IntArray, xs: IntArray, ys: IntArray): Boolean = synchronized(lock) {
        if (pointerIds.isEmpty() || pointerIds.size != xs.size || pointerIds.size != ys.size) {
            return false
        }
        if (pointerIds.toSet().size != pointerIds.size) return false
        val previous = pointerIds.associateWith { id -> pointers[id]?.copy() }
        if (previous.values.any { it == null }) return false
        pointerIds.indices.forEach { index ->
            pointers[pointerIds[index]] = Pointer(xs[index], ys[index])
        }
        if (pointers.values.any { !validCoordinate(it.x, it.y) }) {
            previous.forEach { (id, point) -> pointers[id] = point!! }
            return false
        }
        if (inject(MotionEvent.ACTION_MOVE)) return true
        previous.forEach { (id, point) -> pointers[id] = point!! }
        false
    }

    fun touchUp(pointerId: Int, x: Int, y: Int): Boolean = synchronized(lock) {
        val pointer = pointers[pointerId] ?: return false
        if (!validCoordinate(x, y)) return false
        pointer.x = x
        pointer.y = y
        val index = pointers.keys.indexOf(pointerId)
        val action = if (pointers.size == 1) {
            MotionEvent.ACTION_UP
        } else {
            MotionEvent.ACTION_POINTER_UP or (index shl ACTION_POINTER_INDEX_SHIFT)
        }
        if (!inject(action)) return false
        pointers.remove(pointerId)
        if (pointers.isEmpty()) downTimeMs = 0L
        true
    }

    fun touchCancel(): Boolean = synchronized(lock) {
        if (pointers.isEmpty()) return true
        val result = inject(MotionEvent.ACTION_CANCEL)
        pointers.clear()
        downTimeMs = 0L
        result
    }

    private fun validPointer(pointerId: Int, x: Int, y: Int): Boolean =
        pointerId in 0..31 && validCoordinate(x, y)

    private fun validCoordinate(x: Int, y: Int): Boolean =
        x in 0..MAX_COORDINATE && y in 0..MAX_COORDINATE

    private fun inject(action: Int): Boolean {
        val api = resolveInputApi() ?: return false
        val event = createEvent(action)
        return try {
            api.inject.invoke(api.manager, event, INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH)
                as? Boolean ?: false
        } catch (error: ReflectiveOperationException) {
            Log.w(TAG, "InputManager injection failed", error)
            false
        } finally {
            event.recycle()
        }
    }

    private fun createEvent(action: Int): MotionEvent {
        val properties = Array(pointers.size) { MotionEvent.PointerProperties() }
        val coordinates = Array(pointers.size) { MotionEvent.PointerCoords() }
        pointers.entries.forEachIndexed { index, (id, pointer) ->
            properties[index].id = id
            properties[index].toolType = MotionEvent.TOOL_TYPE_FINGER
            coordinates[index].x = pointer.x.toFloat()
            coordinates[index].y = pointer.y.toFloat()
            coordinates[index].pressure = 1f
            coordinates[index].size = 1f
        }
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(
            downTimeMs,
            now,
            action,
            pointers.size,
            properties,
            coordinates,
            0,
            0,
            1f,
            1f,
            -1,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )
    }

    private fun resolveInputApi(): InputApi? = runCatching {
        val clazz = Class.forName("android.hardware.input.InputManager")
        val manager = clazz.getMethod("getInstance").invoke(null)
            ?: error("InputManager.getInstance returned null")
        val inputEventClass = Class.forName("android.view.InputEvent")
        val inject = clazz.getMethod(
            "injectInputEvent",
            inputEventClass,
            Int::class.javaPrimitiveType!!
        )
        InputApi(manager, inject)
    }.onFailure {
        Log.d(TAG, "Hidden InputManager injection is unavailable", it)
    }.getOrNull()
}

/**
 * Loaded by Shizuku into its own shell-UID process (not this app's process). Needs a public
 * no-arg constructor; Shizuku instantiates it via reflection. Shells out to the platform `input`
 * tool for basic gestures and uses privileged hidden APIs only after capability probing.
 */
class ShizukuTouchService : IShizukuTouchService.Stub() {
    override fun tap(x: Int, y: Int): Boolean =
        runInput(ShizukuInputCommand.args("tap", x.toString(), y.toString()))

    override fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean =
        runInput(
            ShizukuInputCommand.args(
                "swipe",
                fromX.toString(),
                fromY.toString(),
                toX.toString(),
                toY.toString(),
                durationMs.toString()
            )
        )

    override fun keyevent(keyCode: Int): Boolean =
        runInput(ShizukuInputCommand.args("keyevent", keyCode.toString()))

    override fun isDisplayPowerControlAvailable(): Boolean =
        ShizukuDisplayPowerController.isAvailable()

    override fun setDisplayPower(on: Boolean): Boolean =
        ShizukuDisplayPowerController.setDisplayPower(on)

    override fun isRealTouchAvailable(): Boolean =
        ShizukuRealTouchController.isAvailable()

    override fun touchDown(pointerId: Int, x: Int, y: Int): Boolean =
        ShizukuRealTouchController.touchDown(pointerId, x, y)

    override fun touchMove(pointerIds: IntArray, xs: IntArray, ys: IntArray): Boolean =
        ShizukuRealTouchController.touchMove(pointerIds, xs, ys)

    override fun touchUp(pointerId: Int, x: Int, y: Int): Boolean =
        ShizukuRealTouchController.touchUp(pointerId, x, y)

    override fun touchCancel(): Boolean = ShizukuRealTouchController.touchCancel()

    override fun destroy() {
        runCatching { ShizukuRealTouchController.touchCancel() }
        runCatching { ShizukuDisplayPowerController.setDisplayPower(on = true) }
        System.exit(0)
    }

    private fun runInput(args: List<String>): Boolean = ShizukuCommandRunner.run(args)
}
