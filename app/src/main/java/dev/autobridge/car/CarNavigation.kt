package dev.autobridge.car

import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.display.StructuredLog

/**
 * Bookkeeping for which single-instance screens are currently on the car back stack.
 *
 * Kept free of Car App types so the rule can be asserted directly: a marker is present between the
 * push that added it and the pop that destroyed it, and returning to a marker drops everything
 * stacked above it.
 */
class ScreenStackTracker {
    private val stack = ArrayList<String>()

    val markers: List<String> get() = stack.toList()

    fun contains(marker: String): Boolean = stack.contains(marker)

    /** Records a newly pushed screen. */
    fun pushed(marker: String) {
        if (!stack.contains(marker)) stack.add(marker)
    }

    /** Records a destroyed screen, wherever in the stack it was. */
    fun removed(marker: String) {
        stack.remove(marker)
    }

    /**
     * Records a return to [marker]: everything above it is about to be popped, so it leaves the
     * tracker too. Returns false when the marker was not on the stack.
     */
    fun returnedTo(marker: String): Boolean {
        val index = stack.indexOf(marker)
        if (index < 0) return false
        while (stack.size > index + 1) stack.removeAt(stack.size - 1)
        return true
    }

    fun clear() = stack.clear()
}

/**
 * Opens car screens without stacking a second copy of one already open.
 *
 * Most destinations here are reachable from several places — Now Playing from seven, Settings from
 * five, the browser from four — so moving between them with plain `push` built up stacks like
 * Browser → Media → Browser, where Back walks through screens the user already left. Reaching a
 * screen that is already open now returns to that instance instead.
 *
 * Only screens that carry no arguments are routed through here. A paginated or per-item screen
 * (`CarLibraryScreen(mode, entry, page)`, `CarVideoScreen(uri)`) is a genuinely different screen
 * each time and must keep stacking.
 */
object CarNavigation {
    private val tracker = ScreenStackTracker()

    /**
     * Shows the screen identified by [marker], creating it only when it is not already open.
     * [marker] must be stable for the destination; its class name is the natural choice.
     */
    fun open(screenManager: ScreenManager, marker: String, create: () -> Screen) {
        // The live stack decides, not the tracker. The tracker is process-global and is only
        // cleared by Session.onDestroy, so a session that goes away without that callback leaves
        // its markers behind. ScreenManager.popTo() pops everything down to the root when it
        // cannot find the marker, which turned "open this screen" into a silent "go back to Home"
        // for the rest of the process.
        if (screenManager.screenStack.any { it.marker == marker }) {
            tracker.returnedTo(marker)
            StructuredLog.i("CarNav", "return -> $marker")
            screenManager.popTo(marker)
            return
        }
        // Not on the stack: any marker still held for it is stale bookkeeping.
        tracker.removed(marker)
        val screen = runCatching { create() }.onFailure {
            StructuredLog.e("CarNav", "open failed -> $marker: ${it.message}")
        }.getOrThrow()
        screen.marker = marker
        screen.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = tracker.removed(marker)
        })
        tracker.pushed(marker)
        StructuredLog.i("CarNav", "open -> $marker")
        screenManager.push(screen)
    }

    /** Forgets every tracked screen; the car session calls this when its stack goes away. */
    fun reset() = tracker.clear()
}
