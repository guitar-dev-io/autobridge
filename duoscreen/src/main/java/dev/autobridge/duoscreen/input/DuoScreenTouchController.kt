package dev.autobridge.duoscreen.input

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import dev.autobridge.duoscreen.system.DuoScreenPrivilegedOps
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-display pointer bookkeeping: turns tap/drag calls into a correct DOWN/MOVE/UP MotionEvent
 * stream and hands each event to [ops] for injection as the shell UID.
 *
 * Runs in this app's process (the events are built here and travel as one binder transaction),
 * unlike [dev.autobridge.input.ShizukuRealTouchController], which does the same bookkeeping inside
 * a Shizuku user-service process and always targets the default display.
 */
class DuoScreenTouchController(private val ops: DuoScreenPrivilegedOps) {
    private class Pointer(var x: Int, var y: Int) {
        fun copy() = Pointer(x, y)
    }

    private class DisplayState {
        val lock = Any()
        val pointers = LinkedHashMap<Int, Pointer>()
        var downTimeMs = 0L
    }

    private val states = ConcurrentHashMap<Int, DisplayState>()

    /** A complete down/up pair at one point, the common case for a car-surface onClick. */
    fun tap(displayId: Int, x: Int, y: Int): Boolean =
        touchDown(displayId, POINTER_ID, x, y) && touchUp(displayId, POINTER_ID, x, y)

    fun touchDown(displayId: Int, pointerId: Int, x: Int, y: Int): Boolean {
        val state = states.getOrPut(displayId) { DisplayState() }
        synchronized(state.lock) {
            if (!validPointer(pointerId, x, y) ||
                state.pointers.containsKey(pointerId) ||
                state.pointers.size >= MAX_POINTERS
            ) return false

            val wasEmpty = state.pointers.isEmpty()
            if (wasEmpty) state.downTimeMs = SystemClock.uptimeMillis()
            state.pointers[pointerId] = Pointer(x, y)
            val index = state.pointers.keys.indexOf(pointerId)
            val action = if (wasEmpty) {
                MotionEvent.ACTION_DOWN
            } else {
                MotionEvent.ACTION_POINTER_DOWN or (index shl ACTION_POINTER_INDEX_SHIFT)
            }
            if (inject(displayId, state, action)) return true
            state.pointers.remove(pointerId)
            if (wasEmpty) state.downTimeMs = 0L
            return false
        }
    }

    fun touchMove(displayId: Int, pointerIds: IntArray, xs: IntArray, ys: IntArray): Boolean {
        val state = states[displayId] ?: return false
        synchronized(state.lock) {
            if (pointerIds.isEmpty() || pointerIds.size != xs.size || pointerIds.size != ys.size) return false
            if (pointerIds.toSet().size != pointerIds.size) return false
            val previous = pointerIds.associateWith { id -> state.pointers[id]?.copy() }
            if (previous.values.any { it == null }) return false
            pointerIds.indices.forEach { index ->
                state.pointers[pointerIds[index]] = Pointer(xs[index], ys[index])
            }
            if (state.pointers.values.any { !validCoordinate(it.x, it.y) }) {
                previous.forEach { (id, point) -> state.pointers[id] = point!! }
                return false
            }
            if (inject(displayId, state, MotionEvent.ACTION_MOVE)) return true
            previous.forEach { (id, point) -> state.pointers[id] = point!! }
            return false
        }
    }

    fun touchUp(displayId: Int, pointerId: Int, x: Int, y: Int): Boolean {
        val state = states[displayId] ?: return false
        synchronized(state.lock) {
            val pointer = state.pointers[pointerId] ?: return false
            if (!validCoordinate(x, y)) return false
            pointer.x = x
            pointer.y = y
            val index = state.pointers.keys.indexOf(pointerId)
            val action = if (state.pointers.size == 1) {
                MotionEvent.ACTION_UP
            } else {
                MotionEvent.ACTION_POINTER_UP or (index shl ACTION_POINTER_INDEX_SHIFT)
            }
            if (!inject(displayId, state, action)) return false
            state.pointers.remove(pointerId)
            if (state.pointers.isEmpty()) state.downTimeMs = 0L
            return true
        }
    }

    fun cancel(displayId: Int): Boolean {
        val state = states[displayId] ?: return true
        synchronized(state.lock) {
            if (state.pointers.isEmpty()) return true
            val result = inject(displayId, state, MotionEvent.ACTION_CANCEL)
            state.pointers.clear()
            state.downTimeMs = 0L
            return result
        }
    }

    fun cancelAll() {
        states.keys.toList().forEach(::cancel)
    }

    private fun validPointer(pointerId: Int, x: Int, y: Int): Boolean =
        pointerId in 0..31 && validCoordinate(x, y)

    private fun validCoordinate(x: Int, y: Int): Boolean =
        x in 0..MAX_COORDINATE && y in 0..MAX_COORDINATE

    private fun inject(displayId: Int, state: DisplayState, action: Int): Boolean {
        val event = createEvent(state, action)
        return try {
            ops.injectMotion(event, displayId)
        } finally {
            event.recycle()
        }
    }

    private fun createEvent(state: DisplayState, action: Int): MotionEvent {
        val properties = Array(state.pointers.size) { MotionEvent.PointerProperties() }
        val coordinates = Array(state.pointers.size) { MotionEvent.PointerCoords() }
        state.pointers.entries.forEachIndexed { index, (id, pointer) ->
            properties[index].id = id
            properties[index].toolType = MotionEvent.TOOL_TYPE_FINGER
            coordinates[index].x = pointer.x.toFloat()
            coordinates[index].y = pointer.y.toFloat()
            coordinates[index].pressure = 1f
            coordinates[index].size = 1f
        }
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(
            state.downTimeMs,
            now,
            action,
            state.pointers.size,
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

    companion object {
        const val POINTER_ID = 0

        private const val MAX_POINTERS = 10
        private const val MAX_COORDINATE = 10_000
        private const val ACTION_POINTER_INDEX_SHIFT = 8
    }
}
