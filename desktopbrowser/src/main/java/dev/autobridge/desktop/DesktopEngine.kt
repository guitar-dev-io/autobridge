package dev.autobridge.desktop

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.autobridge.desktop.browser.DesktopBrowserAdapter
import dev.autobridge.desktop.browser.DesktopBrowserId
import dev.autobridge.desktop.browser.DesktopBrowsers
import dev.autobridge.desktop.install.BrowserPackages
import dev.autobridge.desktop.install.ComponentState
import dev.autobridge.desktop.install.DesktopEngineInstallService
import dev.autobridge.desktop.install.EngineChoice
import dev.autobridge.desktop.install.EngineStore
import dev.autobridge.desktop.install.InstallState
import dev.autobridge.desktop.install.RuntimeInstaller
import dev.autobridge.desktop.install.RuntimeSource
import dev.autobridge.desktop.install.SafeFiles
import dev.autobridge.desktop.install.VerifiedDownloader
import dev.autobridge.desktop.keyboard.KeyboardInputBridge
import dev.autobridge.desktop.runtime.GuestDnsWatcher
import dev.autobridge.desktop.runtime.LinuxRuntime
import dev.autobridge.desktop.runtime.RuntimeLayout
import dev.autobridge.desktop.session.DesktopSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The desktop browser engine as the rest of the app sees it: install state, operations, and the
 * one running session.
 *
 * Package operations run one at a time on a single worker (they share apt's lock), inside
 * [DesktopEngineInstallService] so leaving the settings screen does not kill a download. Every
 * operation reports through [runtimeState] / [browserStates]; nothing in the UI fakes progress.
 */
object DesktopEngine {
    private lateinit var appContext: Context
    lateinit var layout: RuntimeLayout
        private set
    lateinit var runtime: LinuxRuntime
        private set
    lateinit var store: EngineStore
        private set
    private lateinit var downloader: VerifiedDownloader
    private lateinit var packages: BrowserPackages

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "desktop-engine-ops") }
    private val cancelRequested = AtomicBoolean(false)
    private val queued = AtomicInteger(0)

    private val _runtimeState = MutableStateFlow(ComponentState(InstallState.NOT_INSTALLED))
    val runtimeState: StateFlow<ComponentState> = _runtimeState
    private val _browserStates = MutableStateFlow<Map<DesktopBrowserId, ComponentState>>(emptyMap())
    val browserStates: StateFlow<Map<DesktopBrowserId, ComponentState>> = _browserStates
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _session = MutableStateFlow<DesktopSession?>(null)
    val session: StateFlow<DesktopSession?> = _session

    @Volatile private var initialized = false

    /** Rewrites the guest's resolv.conf on default-network changes while a session exists. */
    private val dnsWatcher by lazy { GuestDnsWatcher(appContext, layout.rootfs) }

    /**
     * The browsers' start page as file name to markup, set by the app (the page is built from its
     * saved sites and strings, which this module cannot see). Read before every launch; null or
     * empty leaves the browsers on their own new tab page.
     */
    @Volatile var startPages: (() -> Map<String, String>)? = null

    /** The Thai family the browsers draw Thai in (GuestFontConfig), read at each launch. Null is the default. */
    @Volatile var thaiFontFamily: (() -> String)? = null

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        layout = RuntimeLayout(appContext)
        runtime = LinuxRuntime(layout)
        store = EngineStore(appContext)
        runtime.seccompAcceleration = store.seccompAcceleration
        downloader = VerifiedDownloader()
        packages = BrowserPackages(runtime, downloader)
        initialized = true
        reconcile()
    }

    /** Device + build can run the engine at all (arm64 phone, PRoot shipped in this APK). */
    val supported: Boolean get() = RuntimeLayout.deviceSupported() && runtime.binariesPresent()

    fun state(id: DesktopBrowserId): ComponentState =
        _browserStates.value[id] ?: ComponentState(InstallState.NOT_INSTALLED)

    var choice: EngineChoice
        get() = store.choice
        set(value) { store.choice = value }

    /** The browser the Desktop Browser entries open, if one is chosen and installed. */
    /** Google Chrome for Linux, whichever browser is chosen for the Web card; null when it is not installed. */
    fun installedChrome(): DesktopBrowserAdapter? =
        DesktopBrowsers.of(DesktopBrowserId.CHROME).takeIf { state(it.id).state.installed }

    fun chosenInstalledBrowser(): DesktopBrowserAdapter? =
        choice.browser?.let(DesktopBrowsers::of)?.takeIf { state(it.id).state.installed }

    // --- Operations -------------------------------------------------------------------------

    fun installRuntime() = submit("runtime") { installRuntimeBlocking() }

    fun installBrowser(id: DesktopBrowserId) = submit(id.key) {
        val adapter = DesktopBrowsers.of(id)
        if (!runtime.rootfsReady()) installRuntimeBlocking()
        if (!runtime.rootfsReady()) {
            setBrowser(id, ComponentState(InstallState.FAILED, lastError = store.runtimeError ?: "Linux runtime is not installed"))
            return@submit
        }
        setBrowser(id, ComponentState(InstallState.DOWNLOADING, 0f))
        try {
            val version = packages.install(adapter, { s -> setBrowser(id, s.copy(version = store.version(id))) }, ::cancelled)
            store.setVersion(id, version)
            store.setError(id, null)
            setBrowser(id, ComponentState(InstallState.READY, version = version))
        } catch (e: Exception) {
            failBrowser(id, e)
        }
    }

    fun checkForUpdates(id: DesktopBrowserId) = submit("check-${id.key}") {
        val adapter = DesktopBrowsers.of(id)
        val current = state(id)
        setBrowser(id, current.copy(state = InstallState.CONFIGURING, progress = null, detail = "apt-get update"))
        try {
            val (installed, candidate) = packages.checkUpdate(adapter, ::cancelled)
            store.setVersion(id, installed)
            store.setCandidate(id, candidate)
            val newer = installed != null && candidate != null && installed != candidate
            setBrowser(id, ComponentState(if (newer) InstallState.UPDATE_AVAILABLE else InstallState.READY, version = installed, candidateVersion = candidate))
        } catch (e: Exception) {
            setBrowser(id, current.copy(lastError = DesktopLog.describe(e)))
        }
    }

    fun updateBrowser(id: DesktopBrowserId) = submit("update-${id.key}") {
        val adapter = DesktopBrowsers.of(id)
        stopSessionIf(id)
        try {
            val version = packages.update(adapter, { s -> setBrowser(id, s.copy(version = store.version(id))) }, ::cancelled)
            store.setVersion(id, version)
            store.setCandidate(id, null)
            store.setError(id, null)
            setBrowser(id, ComponentState(InstallState.READY, version = version))
        } catch (e: Exception) {
            failBrowser(id, e)
        }
    }

    fun removeBrowser(id: DesktopBrowserId, keepProfile: Boolean) = submit("remove-${id.key}") {
        stopSessionIf(id)
        setBrowser(id, ComponentState(InstallState.CONFIGURING, null, "Removing"))
        try {
            packages.remove(DesktopBrowsers.of(id), keepProfile, ::cancelled)
            store.setVersion(id, null)
            store.setCandidate(id, null)
            store.setError(id, null)
            if (choice.browser == id) choice = EngineChoice.AUTOBRIDGE
            setBrowser(id, ComponentState(InstallState.NOT_INSTALLED))
        } catch (e: Exception) {
            failBrowser(id, e)
        }
    }

    /** Deletes the whole runtime, and with it every installed browser and profile. */
    fun removeRuntime() = submit("remove-runtime") {
        stopSession()
        _runtimeState.value = ComponentState(InstallState.CONFIGURING, null, "Removing")
        SafeFiles.deleteTree(layout.base)
        SafeFiles.deleteTree(layout.prootTmp)
        store.runtimeVersion = null
        store.runtimeError = null
        DesktopBrowsers.all.forEach { store.setVersion(it.id, null); store.setCandidate(it.id, null); store.setError(it.id, null) }
        choice = EngineChoice.AUTOBRIDGE
        reconcile()
    }

    fun clearCache(id: DesktopBrowserId) = submit("cache-${id.key}") {
        stopSessionIf(id)
        packages.clearCache(DesktopBrowsers.of(id))
    }

    fun resetProfile(id: DesktopBrowserId) = submit("profile-${id.key}") {
        stopSessionIf(id)
        packages.resetProfile(DesktopBrowsers.of(id))
    }

    fun setSeccompAcceleration(enabled: Boolean) {
        store.seccompAcceleration = enabled
        runtime.seccompAcceleration = enabled
    }

    /** Asks the running operation to stop at its next check (download chunk, extracted file, apt poll). */
    fun cancel() = cancelRequested.set(true)

    // --- Sessions ---------------------------------------------------------------------------

    /**
     * Returns a running session for [id] shown at [viewWidth]×[viewHeight] display pixels with
     * device scale [scale], starting one if needed. Only one browser runs at a time.
     *
     * An existing session of the same browser is reused and simply re-fitted (and, when [startUrl]
     * is given, sent to it in a new tab: a running browser cannot take a command line), unless the new shape
     * does not fit its X screen or the scale differs a lot (phone → car): then the browser is
     * restarted with its last session restored, because Chromium cannot change scale or grow its
     * screen while running.
     */
    @Synchronized
    fun openSession(id: DesktopBrowserId, viewWidth: Int, viewHeight: Int, scale: Float, startUrl: String? = null, forceRestart: Boolean = false): DesktopSession {
        val effective = effectiveScale(id, scale)
        val existing = _session.value
        if (!forceRestart && existing != null && existing.adapter.id == id && existing.isAliveOrStarting() &&
            !existing.tooSmallFor(viewWidth, viewHeight) && kotlin.math.abs(existing.scale - effective) < 0.1f
        ) {
            existing.fitTo(viewWidth, viewHeight)
            startUrl?.let { existing.openInNewTab(it) }
            dnsWatcher.start()
            return existing
        }
        val restore = existing != null && existing.adapter.id == id
        existing?.let { _session.value = null; it.stop() }
        val adapter = DesktopBrowsers.of(id)
        check(state(id).state.installed) { "${adapter.displayName} is not installed" }
        val screen = maxOf(MIN_SCREEN, viewWidth, viewHeight).let { (it + 1) and 1.inv() }
        val session = DesktopSession(appContext, runtime, adapter, screen, effective, restore, scale, store.touchSensitivity)
        _session.value = session
        KeyboardInputBridge.fireReset()
        setBrowser(id, state(id).copy(state = InstallState.RUNNING))
        session.start(startUrl, viewWidth, viewHeight)
        dnsWatcher.start()
        return session
    }

    /** Car/phone base scale × the browser's zoom setting. */
    private fun effectiveScale(id: DesktopBrowserId, base: Float): Float =
        (base * store.zoom(id) / 100f).coerceIn(0.5f, 3f)

    /**
     * Applies a zoom change: the running browser is restarted at the new scale with its tabs
     * restored. Whoever shows it (car screen, phone viewer) follows [session] and re-attaches.
     */
    fun setZoom(id: DesktopBrowserId, percent: Int) {
        store.setZoom(id, percent.coerceIn(ZOOM_MIN, ZOOM_MAX))
        val current = _session.value ?: return
        if (current.adapter.id != id) return
        Thread({
            runCatching { openSession(id, current.width, current.height, current.baseScale, forceRestart = true) }
                .onFailure { DesktopLog.w("zoom restart failed", it) }
        }, "desktop-zoom-restart").start()
    }

    /** Applies at once to the running browser and to every later one. */
    fun setTouchSensitivity(value: Float) {
        store.touchSensitivity = value.coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX)
        _session.value?.input?.sensitivity = store.touchSensitivity
    }

    /**
     * "Start on launch": when AutoBridge starts and the setting is on, the chosen browser is
     * started in the background at the last car page size, so it is already running when the car
     * (or the phone viewer) opens it. Without a screen it is still stopped after [IDLE_STOP_MS].
     */
    fun prestartIfEnabled() {
        if (!initialized || !store.startOnLaunch || _session.value != null || !runtime.rootfsReady()) return
        val browser = chosenInstalledBrowser() ?: return
        val (w, h, scale) = store.lastCar() ?: Triple(1280, 720, 1f)
        DesktopLog.i("start on launch: ${browser.id.key} ${w}x$h")
        runCatching { openSession(browser.id, w, h, scale) }.onFailure { DesktopLog.w("start on launch failed", it) }
        noteSurfaceDetached()
    }

    const val ZOOM_MIN = 50
    const val ZOOM_MAX = 200
    const val SENSITIVITY_MIN = 0.5f
    const val SENSITIVITY_MAX = 1f

    /**
     * The phone shows a desktop-sized page: the long side is about [PHONE_LONG_SIDE] display
     * pixels at scale 1, scaled up to the screen. Portrait gives a tall window, landscape a wide
     * one; both fill the screen.
     */
    fun phoneViewport(surfaceWidth: Int, surfaceHeight: Int): Pair<Int, Int> {
        val k = (maxOf(surfaceWidth, surfaceHeight) / PHONE_LONG_SIDE.toFloat()).coerceAtLeast(1f)
        return (surfaceWidth / k).toInt() to (surfaceHeight / k).toInt()
    }

    /**
     * Car screens are drawn 1:1; the base scale comes from the head unit's dpi, but a wide (or
     * tall-portrait) surface also needs a bigger scale so the page lays out near [TARGET_CSS_WIDTH]
     * CSS pixels instead of a cramped ~1500+ px desktop width with tiny text. CSS layout width =
     * [surfaceWidth] / scale, so the larger of the two factors wins. Landscape only ever gets a
     * larger scale than dpi alone, so it never gets worse. Clamped to a sane desktop range.
     */
    fun carScale(dpi: Int, surfaceWidth: Int): Float =
        maxOf(dpi / 160f, surfaceWidth / TARGET_CSS_WIDTH).coerceIn(1f, 2f)

    private const val TARGET_CSS_WIDTH = 1250f
    private const val PHONE_LONG_SIDE = 1600
    private const val MIN_SCREEN = 1600

    /**
     * Surface bookkeeping for the idle stop: a browser nobody has looked at for [IDLE_STOP_MS]
     * is stopped (its profile is saved on the way out) so it does not keep using memory and
     * battery in the background. Opening it again restores the last session's tabs.
     */
    fun noteSurfaceAttached() = mainHandler.removeCallbacks(idleStop)

    fun noteSurfaceDetached() {
        mainHandler.removeCallbacks(idleStop)
        mainHandler.postDelayed(idleStop, IDLE_STOP_MS)
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val idleStop = Runnable {
        if (_session.value != null) {
            DesktopLog.i("stopping idle desktop browser")
            Thread({ stopSession() }, "desktop-idle-stop").start()
        }
    }
    const val IDLE_STOP_MS = 10 * 60 * 1000L

    fun stopSession() {
        val current = _session.value ?: return
        _session.value = null
        KeyboardInputBridge.fireReset()
        dnsWatcher.stop()
        current.stop()
        setBrowser(current.adapter.id, state(current.adapter.id).copy(state = InstallState.READY))
    }

    private fun stopSessionIf(id: DesktopBrowserId) {
        if (_session.value?.adapter?.id == id) stopSession()
    }

    private fun DesktopSession.isAliveOrStarting(): Boolean =
        status.value.phase == DesktopSession.Phase.STARTING || status.value.phase == DesktopSession.Phase.RUNNING

    // --- Storage and diagnostics ------------------------------------------------------------

    data class Storage(val runtimeTotal: Long, val perBrowser: Map<DesktopBrowserId, Triple<Long, Long, Long>>)

    /** Walks the rootfs; call off the main thread. Per browser: (installed, profile, cache). */
    fun storage(): Storage {
        val total = SafeFiles.sizeOf(layout.base)
        val per = DesktopBrowsers.all.associate { a ->
            a.id to Triple(packages.installedBytes(a), packages.profileBytes(a), packages.cacheBytes(a))
        }
        return Storage(total, per)
    }

    /** RSS of every process this app runs besides itself (the runtime's), in bytes. */
    fun runtimeMemoryBytes(): Long {
        val myUid = android.os.Process.myUid()
        val myPid = android.os.Process.myPid()
        var total = 0L
        File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            if (pid == myPid) return@forEach
            val status = runCatching { File(dir, "status").readLines() }.getOrNull() ?: return@forEach
            val uid = status.firstOrNull { it.startsWith("Uid:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull()
            if (uid != myUid) return@forEach
            val rss = status.firstOrNull { it.startsWith("VmRSS:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: 0
            total += rss * 1024
        }
        return total
    }

    // --- Internals --------------------------------------------------------------------------

    private fun installRuntimeBlocking() {
        try {
            RuntimeInstaller(appContext, runtime, downloader, store).install({ _runtimeState.value = it }, ::cancelled)
            _runtimeState.value = ComponentState(InstallState.READY, version = store.runtimeVersion)
        } catch (e: Exception) {
            DesktopLog.w("runtime install failed", e)
            store.runtimeError = DesktopLog.describe(e)
            _runtimeState.value = ComponentState(InstallState.FAILED, lastError = store.runtimeError)
        }
    }

    private fun reconcile() {
        // An install interrupted mid-extraction leaves only staging/, never a half rootfs.
        SafeFiles.deleteTree(layout.staging)
        _runtimeState.value = when {
            runtime.rootfsReady() -> ComponentState(InstallState.READY, version = store.runtimeVersion ?: RuntimeSource.VERSION_LABEL)
            store.runtimeError != null -> ComponentState(InstallState.FAILED, lastError = store.runtimeError)
            else -> ComponentState(InstallState.NOT_INSTALLED)
        }
        _browserStates.value = DesktopBrowsers.all.associate { a ->
            val installed = runtime.rootfsReady() && packages.isInstalled(a)
            val version = store.version(a.id)
            val candidate = store.candidate(a.id)
            a.id to when {
                installed && candidate != null && candidate != version -> ComponentState(InstallState.UPDATE_AVAILABLE, version = version, candidateVersion = candidate)
                installed -> ComponentState(InstallState.READY, version = version)
                store.error(a.id) != null -> ComponentState(InstallState.FAILED, lastError = store.error(a.id))
                else -> ComponentState(InstallState.NOT_INSTALLED)
            }
        }
    }

    private fun submit(name: String, block: () -> Unit) {
        check(initialized) { "DesktopEngine.init() first" }
        queued.incrementAndGet()
        _busy.value = true
        ContextCompat.startForegroundService(appContext, Intent(appContext, DesktopEngineInstallService::class.java))
        worker.execute {
            cancelRequested.set(false)
            DesktopLog.i("op $name start")
            try {
                block()
            } catch (e: Exception) {
                DesktopLog.w("op $name failed", e)
            } finally {
                DesktopLog.i("op $name end")
                _busy.value = queued.decrementAndGet() > 0
            }
        }
    }

    private fun cancelled(): Boolean = cancelRequested.get()

    private fun setBrowser(id: DesktopBrowserId, state: ComponentState) {
        _browserStates.value = _browserStates.value + (id to state)
    }

    private fun failBrowser(id: DesktopBrowserId, e: Exception) {
        DesktopLog.w("${id.key} operation failed", e)
        val message = DesktopLog.describe(e)
        store.setError(id, message)
        val installed = runtime.rootfsReady() && packages.isInstalled(DesktopBrowsers.of(id))
        setBrowser(id, ComponentState(if (installed) InstallState.READY else InstallState.FAILED, version = store.version(id), lastError = message))
    }

}
