package dev.autobridge.duoscreen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import dev.autobridge.car.DuoSessionState
import dev.autobridge.logging.StructuredLog
import dev.autobridge.power.CarScreenPower
import android.graphics.Bitmap
import android.graphics.Canvas
import dev.autobridge.duoscreen.chrome.ChromeFrame
import dev.autobridge.duoscreen.chrome.ChromeLabels
import dev.autobridge.duoscreen.chrome.ChromeTarget
import dev.autobridge.duoscreen.chrome.DuoScreenChrome
import dev.autobridge.duoscreen.chrome.DuoScreenChromeRenderer
import dev.autobridge.duoscreen.chrome.PaneApp
import dev.autobridge.duoscreen.input.DuoScreenInputPort
import dev.autobridge.duoscreen.input.DuoScreenInputRouter
import dev.autobridge.duoscreen.input.DuoScreenTouchController
import dev.autobridge.duoscreen.layout.DuoScreenLayout
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Bounds
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Rect
import dev.autobridge.duoscreen.layout.DuoScreenLayoutCodec
import dev.autobridge.duoscreen.layout.DuoScreenPane
import dev.autobridge.duoscreen.layout.DuoScreenPaneSet
import dev.autobridge.duoscreen.layout.DuoScreenPreset
import dev.autobridge.duoscreen.layout.DuoScreenStore
import dev.autobridge.duoscreen.render.DuoScreenCompositor
import dev.autobridge.duoscreen.render.DuoScreenDisplays
import dev.autobridge.duoscreen.render.DuoScreenResizeDebouncer
import dev.autobridge.duoscreen.system.DuoScreenLauncherResolver
import dev.autobridge.duoscreen.system.DuoScreenPrivilegedOps
import dev.autobridge.duoscreen.system.DuoScreenSelfPane
import dev.autobridge.duoscreen.system.DuoScreenShizukuOps

/**
 * Owns one Duo Screen session: the panes, their displays, the compositor that draws them into the
 * host's Surface, and the routing of the host's gestures into whichever pane they belong to.
 *
 * Shared by the car Screen and the on-phone development harness so both exercise the same code;
 * the only thing either of them supplies is a Surface and its size.
 */
class DuoScreenController(
    private val context: Context,
    private val ops: DuoScreenPrivilegedOps = DuoScreenShizukuOps
) : DuoScreenInputPort {
    private companion object {
        const val TAG = "AutoBridgeDuoCtl"
        const val QUIET_PERIOD_MS = 300L
        /**
         * How long a seam drag must rest before the panes' displays are resized. Longer than
         * [QUIET_PERIOD_MS] because the car host sends no finger-up: a short pause mid-drag would
         * otherwise resize both displays, and relayout both apps, while the seam is still moving.
         */
        const val DIVIDER_QUIET_PERIOD_MS = 700L

        /** A scroll is replayed into the pane as a short drag with this many interpolated moves. */
        const val SCROLL_STEPS = 4

        const val INPUT_THREAD_NAME = "AutoBridgeDuoInput"

        const val GESTURE_LOG_INTERVAL_MS = 1_000L

        /** How long a tapped bar control shows as pressed. */
        const val PRESS_FLASH_MS = 150L

        /** The seam's toolbar closes itself after this long without a tap on it or a seam drag. */
        const val TOOLBAR_HIDE_MS = 4_000L

        /** Baseline dpi for dp: the car's own density decides every size on the bar. */
        const val BASELINE_DPI = 160f

        /** App icons on the Arrange cards are decoded once at this size and kept. */
        const val ICON_PX = 128
    }

    /** What the car screen has to do for the drawn controls that it alone can: toasts and screens. */
    interface ChromeListener {
        fun onPresetChanged(preset: DuoScreenPreset) {}
        fun onChangeAppRequested(paneId: Int) {}
    }

    var chromeListener: ChromeListener? = null

    private val chromeRenderer = DuoScreenChromeRenderer()

    /** The chrome as last laid out, for hit-testing taps against what is on screen. */
    private var chrome: DuoScreenChrome? = null
    private var pressedChrome: ChromeTarget? = null

    /** The seam's toolbar is open (normal mode only); see [DuoScreenChrome]. */
    private var toolbarOpen = false
    private val hideToolbar = Runnable { closeToolbar() }
    private val appInfo = HashMap<String, PaneApp>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val compositor = DuoScreenCompositor()
    private val touch = DuoScreenTouchController(ops)
    private val debouncer = DuoScreenResizeDebouncer(QUIET_PERIOD_MS)
    private val pendingSizes = HashMap<Int, Rect>()

    private var router: DuoScreenInputRouter? = null

    /**
     * Where touch injection runs. Not the main thread: the car host delivers onClick/onScroll
     * there, and injection is a binder call into Shizuku which can stall on a busy pane app or a
     * wedged service — on the main thread that is the phone's UI frozen, which is what ANR'd.
     *
     * One thread, not a pool, because the events of a gesture are only meaningful in order: a
     * move that overtook its own down would be injected against a pointer that does not exist yet.
     */
    private var inputThread: HandlerThread? = null
    private var inputHandler: Handler? = null
    private var dpi = 0

    /**
     * The density the pane displays actually run at: [dpi] divided by the driver's content scale
     * (see [DuoScreenStore.CONTENT_SCALES]). Kept beside [dpi] rather than replacing it, because
     * the panel's own dpi is what a new scale has to be re-derived from.
     */
    private var paneDpi = 0
    private var running = false
    private var density = 1f
    private var sessionBounds: Bounds? = null
    private var selectedPaneId: Int? = null

    /**
     * Whether the next [stop] is a real teardown (set by [DuoScreenHost.release] and the on-phone
     * harness) rather than [restart]'s internal rebuild. Only a real teardown clears the Duo-active
     * media gate; [restart] leaves this false so the flag stays true across a live rebuild. Read and
     * written only on the controller's calling thread, so it needs no synchronisation.
     */
    internal var tearingDown = false

    /**
     * The host's Surface, kept so the session can be rebuilt on it without waiting for the host to
     * hand it over again — which it has no reason to do when it is the *phone* settings that
     * changed. We do not own it; it is only borrowed for as long as the host says it is valid.
     */
    private var hostSurface: Surface? = null

    /** The preset the live panes were laid out from, so a settings change can tell if it moved. */
    private var appliedPreset: DuoScreenPreset? = null

    val panes: List<DuoScreenPane> get() = router?.panes?.panes.orEmpty()

    /**
     * Starts a session from the driver's own arrangement if they have one, otherwise from the
     * chosen preset ([DuoScreenStore.preset]) laid out on this surface. [fallbackPackages] fills
     * the panes only while no app has been picked for any of them. Panes whose package is null
     * stay empty (nothing is launched into them).
     */
    fun start(
        output: Surface,
        width: Int,
        height: Int,
        panelDpi: Int,
        fallbackPackages: List<String?>
    ): Boolean {
        if (resume(output, width, height, panelDpi)) {
            CarScreenPower.sessionStarted(context)
            DuoSessionState.sessionStarted()
            return true
        }
        stop()
        dpi = panelDpi
        density = densityOf(panelDpi)
        val bounds = DuoScreenChrome.boundsFor(width, height, density)
        paneDpi = storedPaneDpi(panelDpi)
        DuoScreenStore.noteSurface(context, bounds)
        // An arrangement saved before the control bar existed tiles the surface edge to edge; it
        // would get the bar drawn over its panes, so it is laid out afresh from the preset instead.
        val restored = DuoScreenStore.restore(context, bounds)?.let { saved ->
            if (DuoScreenChrome.hasRoomForBar(saved, bounds)) saved
            else null.also { StructuredLog.i(TAG, "Saved arrangement has no room for the bar; using the preset") }
        }
        val preset = DuoScreenStore.presetPanes(context, bounds)
        val paneSet = when {
            restored != null -> DuoScreenPaneSet.of(restored)
            preset.any { it.packageName != null } -> DuoScreenPaneSet.of(preset)
            fallbackPackages.isNotEmpty() -> DuoScreenPaneSet.of(
                DuoScreenStore.preset(context).rects(fallbackPackages.size, bounds)
                    .mapIndexed { index, rect ->
                        DuoScreenPane(index, fallbackPackages[index], rect)
                    }
            )
            else -> return false
        }
        if (!compositor.attachBlocking(output, width, height)) {
            StructuredLog.e(TAG, "Compositor could not bind the host surface")
            return false
        }
        running = true
        sessionBounds = bounds
        hostSurface = output
        appliedPreset = DuoScreenStore.preset(context)

        router = DuoScreenInputRouter(paneSet, bounds, this)
        paneSet.panes.forEach(::openPane)
        refreshChrome()
        // The panes run on untrusted VirtualDisplays, which the system stops resuming once the
        // phone sleeps or locks, so the display has to be held awake for as long as the session
        // lives. Only the panel is allowed to go dark, and only if the user asked for that; see
        // CarScreenPower. Deliberately not in detach(): a detached session's panes are still
        // running, and the keep-alive window is exactly when the driver is looking at Maps with
        // the phone untouched.
        CarScreenPower.sessionStarted(context)
        DuoSessionState.sessionStarted()
        StructuredLog.i(
            TAG,
            "Session started: ${paneSet.panes.size} panes on ${width}x$height" +
                if (restored != null) " (restored)" else " (${DuoScreenStore.preset(context).name})"
        )
        return true
    }

    /**
     * Re-lays the panes out as [preset] and makes it the stored choice. The displays behind them
     * resize on the same debounced path a drag uses, so this costs no more than a big drag would.
     */
    fun applyPreset(preset: DuoScreenPreset): Boolean {
        val bounds = sessionBounds ?: return false
        val active = router ?: return false
        val byId = active.panes.panes.sortedBy { it.id }
        val rects = preset.rects(byId.size, bounds)
        byId.forEachIndexed { index, pane -> active.setRect(pane.id, rects[index]) }
        // Picture-in-picture only reads as one app over another if the tiles are on top, and the
        // pane set's z-order (last = topmost) is what the compositor draws by.
        byId.drop(1).forEach { pane ->
            active.bringToFront(pane.id)
            compositor.bringToFront(pane.id)
        }
        DuoScreenStore.setPreset(context, preset)
        refreshChrome()
        StructuredLog.i(TAG, "Applied preset ${preset.name} to ${byId.size} panes")
        return true
    }

    /** Lays the panes back out on the stored preset, discarding a hand-made arrangement. */
    fun reapplyPreset(): Boolean {
        val preset = DuoScreenStore.preset(context)
        if (!applyPreset(preset)) return false
        appliedPreset = preset
        return true
    }

    /** Steps to the next preset and returns it, for a caller driving this from one button. */
    fun cyclePreset(): DuoScreenPreset {
        val next = DuoScreenStore.preset(context).next()
        // Without a live session there is nothing to re-lay out, but the choice still sticks and
        // the next session starts on it.
        if (!applyPreset(next)) DuoScreenStore.setPreset(context, next) else appliedPreset = next
        return next
    }

    /**
     * Picks the live session back up on a new host Surface instead of building a new one, which is
     * what keeps the pane apps — and whatever the driver was doing in them — alive across the host
     * taking its Surface away, and across leaving Duo Screen and coming back.
     *
     * Returns false when there is nothing to resume, and the caller starts a session from scratch.
     * A surface of a different size is still resumed: the panes are re-fitted to it, the same way
     * [DuoScreenStore.restore] re-fits a saved layout onto another panel.
     */
    private fun resume(output: Surface, width: Int, height: Int, panelDpi: Int): Boolean {
        if (!running || !compositor.hasContext) return false
        val active = router ?: return false
        val previous = sessionBounds ?: return false
        if (!compositor.attachBlocking(output, width, height)) {
            StructuredLog.w(TAG, "Could not re-bind the surface; restarting the session")
            return false
        }
        dpi = panelDpi
        density = densityOf(panelDpi)
        paneDpi = storedPaneDpi(panelDpi)
        hostSurface = output
        val bounds = DuoScreenChrome.boundsFor(width, height, density)
        sessionBounds = bounds
        if (bounds != previous) {
            DuoScreenLayoutCodec.refit(active.panes.panes.sortedBy { it.id }, previous, bounds)
                .forEach { pane -> active.setRect(pane.id, pane.rect) }
        }
        refreshChrome()
        StructuredLog.i(
            TAG,
            "Session resumed on ${width}x$height" +
                if (bounds != previous) " (re-fitted from ${previous.width}x${previous.height})" else ""
        )
        return true
    }

    /**
     * Gives the host its Surface back without ending the session: the pane displays, the apps in
     * them and the arrangement all stay, so the next [start] resumes instead of relaunching.
     * [stop] is the real teardown.
     */
    fun detach() {
        if (!running) return
        saveLayout()
        mainHandler.removeCallbacks(commitPoll)
        // Kept alive, unlike in stop(): the session is only detached and resume() carries on with it.
        onInputThread { touch.cancelAll() }
        compositor.detachBlocking()
        StructuredLog.i(TAG, "Surface detached; ${panes.size} pane(s) kept alive")
    }

    /**
     * The panel's dpi divided by the stored content scale, which is what gives the app in a pane
     * more dp to lay itself out in without anything being scaled after the fact. Floored at 1
     * because a VirtualDisplay rejects a density of zero.
     */
    private fun storedPaneDpi(baseDpi: Int): Int =
        (baseDpi * 100 / DuoScreenStore.contentScale(context)).coerceAtLeast(1)

    private fun openPane(pane: DuoScreenPane) {
        val surface = compositor.addPaneBlocking(pane.id, pane.rect) ?: run {
            StructuredLog.e(TAG, "Pane ${pane.id} got no compositor surface")
            return
        }
        val displayId = DuoScreenDisplays.create(
            context, pane.id, surface, pane.rect.width, pane.rect.height, paneDpi, ops
        )
        if (displayId < 0) {
            StructuredLog.e(TAG, "Pane ${pane.id} got no display")
            return
        }
        val packageName = pane.packageName ?: return
        if (!ops.isAvailable) {
            StructuredLog.w(TAG, "Pane ${pane.id}: Shizuku is not granted, $packageName not launched")
            return
        }
        val isSelf = DuoScreenSelfPane.isSelf(packageName, context.packageName)
        val component = resolveLauncherComponent(packageName)
        val launched = ops.launchOnDisplay(displayId, packageName, component, allowSecondInstance = isSelf)
        StructuredLog.i(
            TAG,
            "Pane ${pane.id}: launch($packageName on display $displayId) = $launched" +
                " (${if (component != null) "explicit $component" else "implicit fallback"}" +
                "${if (isSelf) ", own app in its own task" else ""})"
        )
    }

    /**
     * Re-reads the phone-side settings and applies them to the running session, so choosing a
     * different app for a pane changes that pane now rather than at the next connection. Returns
     * true when a live session took the change.
     *
     * A pane whose app changed is torn down and reopened — new display, new launch — rather than
     * having the new app started over the old one, which would leave the previous app's task
     * sitting on the same display behind it. A different pane *count* is not a pane change at all
     * but a different layout, so that rebuilds the whole session on the surface we already hold.
     */
    fun applyStoredSettings(): Boolean {
        if (!running) return false
        val active = router ?: return false
        val live = active.panes.panes.sortedBy { it.id }
        val stored = DuoScreenStore.packages(context)
        if (stored.size != live.size) return restart()

        val preset = DuoScreenStore.preset(context)
        // Only when it actually moved: re-applying it would throw away an arrangement the driver
        // dragged out by hand, which the settings screen never asked to touch.
        if (preset != appliedPreset) {
            applyPreset(preset)
            appliedPreset = preset
        }

        // A changed content scale is a density change and nothing else: the panes keep their rects,
        // so there is no layout to redo — each live display is simply re-run at the new density.
        val scaled = storedPaneDpi(dpi)
        if (scaled != paneDpi) {
            paneDpi = scaled
            live.forEach { pane ->
                val size = DuoScreenDisplays.size(pane.id) ?: return@forEach
                DuoScreenDisplays.resize(pane.id, size.width, size.height, paneDpi)
            }
            StructuredLog.i(TAG, "Pane content scale applied: ${paneDpi}dpi from a ${dpi}dpi panel")
        }

        val changed = active.panes.panesWithOtherPackage(stored)
        changed.forEach { paneId ->
            active.setPackage(paneId, stored.getOrNull(paneId))
            active.panes.pane(paneId)?.let(::reopenPane)
        }
        saveLayout()
        refreshChrome()
        StructuredLog.i(
            TAG,
            "Settings applied live: ${changed.size} pane(s) swapped, preset=${preset.name}"
        )
        return true
    }

    /** Rebuilds the session from scratch on the Surface the host has already given us. */
    private fun restart(): Boolean {
        val output = hostSurface ?: return false
        val bounds = sessionBounds ?: return false
        val panelDpi = dpi
        // Checked before the teardown, not after: with no usable surface to rebuild on, tearing
        // the session down would lose the panes and put nothing in their place.
        if (!output.isValid) {
            StructuredLog.w(TAG, "The host surface is gone; the session is left for the next entry")
            return false
        }
        // This stop() is a rebuild, not an end: do NOT clear the Duo-active media gate.
        tearingDown = false
        stop()
        return start(output, bounds.width, bounds.height, panelDpi, emptyList())
    }

    /**
     * Replaces everything behind one pane: its display goes first, so the old app's windows leave
     * with it, and the compositor's texture is rebuilt before the new display renders into it.
     */
    private fun reopenPane(pane: DuoScreenPane) {
        DuoScreenDisplays.release(pane.id)
        debouncer.cancel(pane.id)
        pendingSizes.remove(pane.id)
        openPane(pane)
    }

    /** Re-launches a pane's app, for the "pane went black" reload action. */
    fun reload(paneId: Int): Boolean {
        val pane = router?.panes?.pane(paneId) ?: return false
        val packageName = pane.packageName ?: return false
        val displayId = DuoScreenDisplays.displayId(paneId)
        if (displayId < 0) return false
        return ops.launchOnDisplay(
            displayId,
            packageName,
            resolveLauncherComponent(packageName),
            allowSecondInstance = DuoScreenSelfPane.isSelf(packageName, context.packageName),
        )
    }

    /**
     * Resolves [packageName]'s launcher activity to an explicit [ComponentName] with the app's own
     * package visibility (the QUERY_ALL_PACKAGES the personal/lab manifest declares, same as the
     * pane picker). Returns null when nothing resolves — then [DuoScreenShizukuOps.launchOnDisplay]
     * falls back to the implicit intent resolved shell-side. A null is logged, naming the package,
     * so a resulting blank pane is diagnosable rather than silent.
     */
    private fun resolveLauncherComponent(packageName: String): ComponentName? {
        // Our own app resolves to MainActivity, the phone's home UI, which is the wrong screen for
        // a pane; DuoScreenSelfPane names the right one.
        DuoScreenSelfPane.activityOrNull(packageName, context.packageName)?.let { activity ->
            return ComponentName(packageName, activity)
        }
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)
        val candidates = queryLaunchableActivities(context.packageManager, intent).mapNotNull { info ->
            val activity = info.activityInfo ?: return@mapNotNull null
            DuoScreenLauncherResolver.LauncherActivity(activity.packageName, activity.name)
        }
        val activity = DuoScreenLauncherResolver.explicitActivityOrNull(packageName, candidates)
        if (activity == null) {
            StructuredLog.w(
                TAG,
                "No launcher activity resolved for $packageName" +
                    " (${candidates.size} MAIN/LAUNCHER candidate(s)); falling back to implicit launch"
            )
            return null
        }
        return ComponentName(activity.packageName, activity.activityName)
    }

    private fun queryLaunchableActivities(pm: PackageManager, intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }

    /** Reloads the selected pane, or every pane when nothing is selected. Returns how many ran. */
    fun reloadSelectedOrAll(): Int {
        val selected = selectedPaneId
        if (selected != null) return if (reload(selected)) 1 else 0
        return panes.count { reload(it.id) }
    }

    val mode: DuoScreenInputRouter.Mode
        get() = router?.mode ?: DuoScreenInputRouter.Mode.NORMAL

    fun setMode(mode: DuoScreenInputRouter.Mode) {
        if (mode == DuoScreenInputRouter.Mode.EDIT && toolbarOpen) {
            toolbarOpen = false
            mainHandler.removeCallbacks(hideToolbar)
        }
        router?.setMode(mode)
        refreshChrome()
    }

    /** Returns the mode now in effect, for a caller that drives this from one button. */
    fun toggleMode(): DuoScreenInputRouter.Mode {
        val next = if (mode == DuoScreenInputRouter.Mode.EDIT) {
            DuoScreenInputRouter.Mode.NORMAL
        } else {
            DuoScreenInputRouter.Mode.EDIT
        }
        setMode(next)
        return next
    }

    // --- host gestures, forwarded to the router which decides what they mean in the current mode ---

    fun onClick(x: Int, y: Int) {
        val active = router
        if (active == null) {
            StructuredLog.w(TAG, "Car tap at $x,$y dropped: no live session")
            return
        }
        StructuredLog.i(TAG, "Car tap at $x,$y (${active.mode})")
        // The drawn controls sit over the panes and the gaps between them, so they are asked first.
        val shown = chrome
        val target = shown?.hit(x, y)
        if (target != null) {
            onChromeTap(target)
            return
        }
        // An open toolbar closes on a tap anywhere else, and that tap goes no further: it was
        // meant for the toolbar, not for whatever pane is under the finger.
        if (toolbarOpen) {
            closeToolbar()
            return
        }
        // The rest of the bar belongs to no pane: swallowed rather than sent to the nearest one.
        if (shown?.onBar(x, y) == true) return
        active.onClick(x, y)
        refreshChrome()
    }

    private fun onChromeTap(target: ChromeTarget) {
        StructuredLog.i(TAG, "Chrome tap: $target")
        when (target) {
            is ChromeTarget.Chip -> {
                if (applyPreset(target.preset)) appliedPreset = target.preset
            }
            is ChromeTarget.ChangeApp -> chromeListener?.onChangeAppRequested(target.paneId)
            is ChromeTarget.Control -> when (target.kind) {
                ChromeTarget.Kind.LAYOUT -> chromeListener?.onPresetChanged(cyclePreset())
                ChromeTarget.Kind.SWAP -> swapPanes()
                ChromeTarget.Kind.RELOAD -> StructuredLog.i(TAG, "Reloaded ${reloadSelectedOrAll()} pane(s)")
                ChromeTarget.Kind.ARRANGE -> setMode(DuoScreenInputRouter.Mode.EDIT)
                ChromeTarget.Kind.DONE -> setMode(DuoScreenInputRouter.Mode.NORMAL)
                ChromeTarget.Kind.GRIP ->
                    if (mode == DuoScreenInputRouter.Mode.EDIT) chrome?.barDivider?.let { router?.grabDivider(it) }
                    else if (toolbarOpen) closeToolbar() else openToolbar()
            }
        }
        // Using the toolbar keeps it up; Arrange replaces it with the arrange controls.
        if (toolbarOpen && target is ChromeTarget.Control && target.kind != ChromeTarget.Kind.GRIP) {
            if (mode == DuoScreenInputRouter.Mode.EDIT) closeToolbar() else scheduleToolbarHide()
        }
        // A short pressed flash, so a tap on a drawn button is seen to have landed.
        pressedChrome = target
        refreshChrome()
        mainHandler.postDelayed({
            pressedChrome = null
            refreshChrome()
        }, PRESS_FLASH_MS)
    }

    /**
     * Opens the seam's toolbar and grabs the seam, so a drag straight after the tap on the handle
     * resizes the panes: the handle is both the way to the buttons and the seam's own grip.
     */
    private fun openToolbar() {
        toolbarOpen = true
        chrome?.barDivider?.let { router?.grabDivider(it) }
        scheduleToolbarHide()
    }

    private fun closeToolbar() {
        mainHandler.removeCallbacks(hideToolbar)
        if (!toolbarOpen) return
        toolbarOpen = false
        router?.releaseGrab()
        refreshChrome()
    }

    private fun scheduleToolbarHide() {
        mainHandler.removeCallbacks(hideToolbar)
        mainHandler.postDelayed(hideToolbar, TOOLBAR_HIDE_MS)
    }

    /**
     * Swaps the two panes on either side of the bar — their places and sizes, each keeping its
     * app — or the first two panes when the bar has no seam (picture-in-picture: the tile and the
     * main pane trade places).
     */
    fun swapPanes(): Boolean {
        val active = router ?: return false
        val seam = chrome?.barDivider
        val ids = if (seam != null) listOf(seam.first, seam.second) else active.panes.panes.map { it.id }.sorted().take(2)
        if (ids.size < 2) return false
        val a = active.panes.pane(ids[0]) ?: return false
        val b = active.panes.pane(ids[1]) ?: return false
        active.setRect(a.id, b.rect)
        active.setRect(b.id, a.rect)
        // Whichever is now the smaller one goes on top, so a swapped picture-in-picture stays one.
        val smaller = listOf(a.id, b.id).minByOrNull { id -> active.panes.pane(id)?.rect?.let { it.width.toLong() * it.height } ?: 0L }
        smaller?.let {
            active.bringToFront(it)
            compositor.bringToFront(it)
        }
        refreshChrome()
        StructuredLog.i(TAG, "Swapped panes ${a.id} and ${b.id}")
        return true
    }

    // --- the drawn chrome ---

    private fun densityOf(panelDpi: Int): Float = (if (panelDpi > 0) panelDpi else BASELINE_DPI.toInt()) / BASELINE_DPI

    /**
     * Lays the chrome out for the panes as they are now and hands the compositor a painter for it.
     * Everything the painter reads is captured here, because it runs later on the GL thread.
     */
    private fun refreshChrome() {
        if (!running) return
        val active = router ?: return
        val bounds = sessionBounds ?: return
        val labels = ChromeLabels(
            swap = context.getString(R.string.duo_swap),
            done = context.getString(R.string.duo_done),
            changeApp = context.getString(R.string.duo_change_app),
            chips = mapOf(
                DuoScreenPreset.EVEN_COLUMNS to context.getString(R.string.duo_chip_side_by_side),
                DuoScreenPreset.STACKED_60_40 to context.getString(R.string.duo_chip_stacked),
                DuoScreenPreset.PICTURE_IN_PICTURE to context.getString(R.string.duo_chip_pip)
            )
        )
        val panes = active.panes.panes
        val editing = active.mode == DuoScreenInputRouter.Mode.EDIT
        val laidOut = DuoScreenChrome.compute(
            panes, bounds, density, editing, labels, chromeRenderer::measure,
            toolbarOpen = toolbarOpen && !editing
        )
        chrome = laidOut
        val apps = if (editing) panes.associate { it.id to appFor(it.packageName) } else emptyMap()
        val titles = if (editing) panes.associate { pane ->
            pane.id to context.getString(R.string.duo_card_title, pane.id + 1, apps[pane.id]?.label.orEmpty())
        } else emptyMap()
        val shares = if (editing) laidOut.cards.associate { card ->
            card.paneId to context.getString(
                when (card.shareAxis) {
                    DuoScreenChrome.ShareAxis.HEIGHT -> R.string.duo_share_height
                    DuoScreenChrome.ShareAxis.WIDTH -> R.string.duo_share_width
                    DuoScreenChrome.ShareAxis.AREA -> R.string.duo_share_area
                },
                card.sharePercent
            )
        } else emptyMap()
        val frame = ChromeFrame(
            chrome = laidOut,
            density = density,
            focusedPaneId = active.lastTouchedPaneId,
            selectedPaneId = active.selectedPaneId,
            gripGrabbed = active.divider != null,
            pressed = pressedChrome,
            currentPreset = DuoScreenStore.preset(context),
            titles = titles,
            shares = shares,
            apps = apps,
            labels = labels
        )
        val renderer = chromeRenderer
        compositor.setOverlay { canvas: Canvas -> renderer.draw(canvas, frame) }
    }

    /** A pane app's name and icon for its Arrange card, loaded once per package. */
    private fun appFor(packageName: String?): PaneApp {
        if (packageName == null) return PaneApp(context.getString(R.string.duo_screen_pane_empty), null)
        appInfo[packageName]?.let { return it }
        val pm = context.packageManager
        val loaded = if (DuoScreenSelfPane.isSelf(packageName, context.packageName)) {
            PaneApp(context.getString(R.string.duo_screen_self_pane), iconBitmap { pm.getApplicationIcon(packageName) })
        } else {
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }
                .getOrDefault(packageName)
            PaneApp(label, iconBitmap { pm.getApplicationIcon(packageName) })
        }
        appInfo[packageName] = loaded
        return loaded
    }

    private fun iconBitmap(load: () -> android.graphics.drawable.Drawable): Bitmap? = runCatching {
        val drawable = load()
        Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888).also { bitmap ->
            drawable.setBounds(0, 0, ICON_PX, ICON_PX)
            drawable.draw(Canvas(bitmap))
        }
    }.getOrNull()

    fun onScroll(dx: Int, dy: Int) {
        logGesture("scroll", "$dx,$dy")
        router?.onScroll(dx, dy)
    }

    fun onFling(velocityX: Int, velocityY: Int) {
        logGesture("fling", "$velocityX,$velocityY")
        router?.onFling(velocityX, velocityY)
    }

    fun onScale(focusX: Int, focusY: Int, scaleFactor: Float) {
        logGesture("scale", "focus $focusX,$focusY x$scaleFactor")
        router?.onScale(focusX, focusY, scaleFactor)
    }

    private var lastGestureLogMs = 0L

    /**
     * Writes the first drag, fling or pinch the car host sends in each second. A head unit that
     * never delivers one of them is the difference between a layout that can be dragged on the
     * desktop head unit and one that cannot in the car, and without this line the log could not
     * say which it is. Throttled because a drag is dozens of events.
     */
    private fun logGesture(kind: String, detail: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastGestureLogMs < GESTURE_LOG_INTERVAL_MS) return
        lastGestureLogMs = now
        StructuredLog.i(TAG, "Car $kind $detail (${mode})")
    }

    /**
     * Ends the whole session, including every pane's VirtualDisplay, and so relaunches every pane
     * app next time. A host Surface coming and going is **not** this: that is [detach] and
     * [resume], which keep the context, the displays and the running apps.
     *
     * This used to be what onSurfaceDestroyed did, on the reasoning that a display renders into a
     * SurfaceTexture owned by the compositor's EGL context and that context dies with the host
     * Surface. Only the EGLSurface has to die with it — the context, and everything it owns,
     * outlives the window it was drawn into (see [DuoScreenCompositor.attachBlocking]).
     */
    fun stop() {
        if (!running) return
        saveLayout()
        running = false
        selectedPaneId = null
        toolbarOpen = false
        mainHandler.removeCallbacksAndMessages(null)
        chromeRefreshPosted = false
        loggedSeam = null
        debouncer.cancelAll()
        pendingSizes.clear()
        releaseInputThread()
        router?.panes?.panes?.forEach { pane ->
            compositor.removePane(pane.id)
            DuoScreenDisplays.release(pane.id)
        }
        router = null
        sessionBounds = null
        compositor.stopBlocking()
        CarScreenPower.sessionEnded()
        // Clear the Duo-active media gate ONLY on real teardown, never on restart()'s internal
        // stop(): restart() leaves tearingDown false so the flag stays true across a live rebuild;
        // a true teardown (release()/harness) sets it true so the flag drops exactly once.
        if (tearingDown) DuoSessionState.sessionEnded()
        StructuredLog.i(TAG, "Session stopped")
    }

    private fun saveLayout() {
        val bounds = sessionBounds ?: return
        val current = router?.panes?.panes ?: return
        if (current.isEmpty()) return
        // Saved by pane id, not z-order, so a restored session keeps pane 0 as pane 0.
        DuoScreenStore.save(context, bounds, current.sortedBy { it.id })
    }

    /** Runs [work] on [inputThread], starting it if this is the first event of a session. */
    private fun onInputThread(work: () -> Unit) {
        val live = inputHandler?.takeIf { inputThread?.isAlive == true }
        val handler = live ?: run {
            val thread = HandlerThread(INPUT_THREAD_NAME).also { it.start() }
            inputThread = thread
            Handler(thread.looper).also { inputHandler = it }
        }
        handler.post { work() }
    }

    /**
     * Ends the input thread, letting what is already queued run first — the last thing queued is
     * the pointer-release, and dropping it would leave a pane's app believing a finger is still
     * down on it.
     */
    private fun releaseInputThread() {
        val handler = inputHandler
        if (handler == null) {
            touch.cancelAll()
        } else {
            handler.post { touch.cancelAll() }
            inputThread?.quitSafely()
        }
        inputHandler = null
        inputThread = null
    }

    // --- DuoScreenInputPort ---

    override fun forwardTap(paneId: Int, localX: Int, localY: Int) {
        CarScreenPower.carInput()
        val displayId = DuoScreenDisplays.displayId(paneId)
        if (displayId < 0) {
            StructuredLog.w(TAG, "Tap on pane $paneId dropped: it has no display")
            return
        }
        val pane = router?.panes?.pane(paneId) ?: return
        val size = DuoScreenDisplays.size(paneId) ?: return
        // The router hands over pane-local surface pixels; the display may be running at another
        // size, in which case the image is stretched and the touch has to be stretched with it.
        val (x, y) = DuoScreenLayout.scaleToDisplay(localX, localY, pane.rect, size.width, size.height)
        // Geometry is read here, on the caller's thread, where the pane set and the displays are
        // written; only the injection itself goes to the input thread.
        onInputThread {
            val injected = touch.tap(displayId, x, y)
            // A refused injection is silent otherwise: it is what an unavailable Shizuku looks
            // like from the car, a screen that simply does not react.
            StructuredLog.i(
                TAG,
                "Tap on pane $paneId -> display $displayId at $x,$y injected=$injected" +
                    " (shizuku=${ops.isAvailable})"
            )
        }
    }

    /**
     * The host reports a scroll as a distance, with no position (see [DuoScreenInputRouter]), so it
     * is replayed as a drag starting at the pane's centre — the only point guaranteed to be inside
     * the pane — moving by the opposite of the scrolled distance, the direction content travels.
     */
    override fun forwardScroll(paneId: Int, dx: Int, dy: Int) {
        CarScreenPower.carInput()
        val displayId = DuoScreenDisplays.displayId(paneId)
        val pane = router?.panes?.pane(paneId) ?: return
        if (displayId < 0) return
        // Centre and distance both belong to the display's coordinate space, not the pane rect's;
        // see DuoScreenLayout.scaleToDisplay.
        val size = DuoScreenDisplays.size(paneId) ?: return
        val centreX = size.width / 2
        val centreY = size.height / 2
        val (scrollX, scrollY) =
            DuoScreenLayout.scaleDeltaToDisplay(dx, dy, pane.rect, size.width, size.height)
        // The whole drag is one unit of work on the input thread, so its down, moves and up stay
        // in order and none of the six injections it costs lands on the caller's thread.
        onInputThread {
            if (!touch.touchDown(
                    displayId, DuoScreenTouchController.POINTER_ID, centreX, centreY
                )
            ) return@onInputThread
            val ids = intArrayOf(DuoScreenTouchController.POINTER_ID)
            for (step in 1..SCROLL_STEPS) {
                val x = centreX - scrollX * step / SCROLL_STEPS
                val y = centreY - scrollY * step / SCROLL_STEPS
                touch.touchMove(displayId, ids, intArrayOf(x), intArrayOf(y))
            }
            touch.touchUp(
                displayId, DuoScreenTouchController.POINTER_ID, centreX - scrollX, centreY - scrollY
            )
        }
    }

    override fun onDividerGrabbed(divider: DuoScreenLayout.Divider?) {
        // The overlay draws the grabbed grip; the old filled band over the seam is not needed.
        scheduleChromeRefresh()
        // This is called again on every step of a seam drag, to move the grip with it. Only a grab
        // or a release is worth a line: the log is mirrored to a file, and a write per drag event
        // was part of what made the seam lag behind the finger.
        val seam = divider?.let { Triple(it.first, it.second, it.axis) }
        if (seam == loggedSeam) return
        loggedSeam = seam
        StructuredLog.i(
            TAG,
            divider?.let { "Grabbed the ${it.axis} seam between panes ${it.first} and ${it.second}" }
                ?: "Released the seam"
        )
    }

    override fun onSelectionChanged(paneId: Int?) {
        selectedPaneId = paneId
        // The selection outline is drawn by the overlay, above its dimmed card.
        refreshChrome()
        // Keep the compositor's draw order in step with the pane set's: the router has already
        // moved the selected pane to the front of its own z-order.
        paneId?.let(compositor::bringToFront)
        StructuredLog.i(TAG, "Selected pane: ${paneId ?: "none"}")
    }

    /**
     * The quad moves now so the image follows the finger; the VirtualDisplay behind it is only
     * resized once [DuoScreenResizeDebouncer] reports the gesture has been quiet, because that is
     * the expensive half and the hosted app relayouts for every size it is given.
     */
    override fun onPaneRectChanged(paneId: Int, rect: Rect) {
        compositor.setRect(paneId, rect)
        // Dragging the seam from the toolbar's handle is using it: keep it up until the drag ends.
        if (toolbarOpen) scheduleToolbarHide()
        scheduleChromeRefresh()
        pendingSizes[paneId] = rect
        val quiet = if (router?.divider != null) DIVIDER_QUIET_PERIOD_MS else QUIET_PERIOD_MS
        debouncer.onResizeActivity(paneId, SystemClock.uptimeMillis(), quiet)
        mainHandler.removeCallbacks(commitPoll)
        mainHandler.postDelayed(commitPoll, quiet)
    }

    private var loggedSeam: Triple<Int, Int, DuoScreenLayout.Axis>? = null
    private var chromeRefreshPosted = false
    private val chromeRefresh = Runnable {
        chromeRefreshPosted = false
        refreshChrome()
    }

    /**
     * One seam step reports both panes' new rects and the moved grip - three calls that each
     * used to lay the chrome out and repaint the whole overlay. They now ask for one refresh,
     * run once the step is done.
     */
    private fun scheduleChromeRefresh() {
        if (chromeRefreshPosted) return
        chromeRefreshPosted = true
        mainHandler.post(chromeRefresh)
    }

    // Explicit type: the lambda reschedules itself, which inference cannot resolve on its own.
    private val commitPoll: Runnable = Runnable {
        debouncer.pollReadyToCommit(SystemClock.uptimeMillis()).forEach { paneId ->
            val rect = pendingSizes.remove(paneId) ?: return@forEach
            // A pane that was dragged but not resized has nothing to commit, and committing it
            // anyway is a configuration change the hosted app answers with a blank relayout —
            // the blink after every move. Only a real size change goes through.
            if (!DuoScreenDisplays.needsResize(paneId, rect.width, rect.height, paneDpi)) return@forEach
            compositor.commitSize(paneId, rect.width, rect.height)
            DuoScreenDisplays.resize(paneId, rect.width, rect.height, paneDpi)
            StructuredLog.i(TAG, "Pane $paneId committed ${rect.width}x${rect.height}")
        }
        saveLayout()
        if (pendingSizes.isNotEmpty()) {
            mainHandler.postDelayed(commitPoll, if (router?.divider != null) DIVIDER_QUIET_PERIOD_MS else QUIET_PERIOD_MS)
        }
    }
}
