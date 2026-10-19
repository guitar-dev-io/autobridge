package dev.autobridge.desktop.session

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import android.view.Surface
import dev.autobridge.desktop.DesktopLog
import dev.autobridge.desktop.audio.BrowserAudioBridge
import dev.autobridge.desktop.browser.DesktopBrowserAdapter
import dev.autobridge.desktop.browser.LaunchSpec
import dev.autobridge.desktop.display.FrameRenderer
import dev.autobridge.desktop.display.X11Connection
import dev.autobridge.desktop.display.XwdFramebuffer
import dev.autobridge.desktop.input.BrowserInput
import dev.autobridge.desktop.runtime.LinuxRuntime
import dev.autobridge.desktop.runtime.RuntimeLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One running desktop browser: its own Xvfb display, PulseAudio and browser process tree, plus
 * the bridges that show it on a Surface and feed it input and audio.
 *
 * The X screen is a [screenSize] square, big enough for any shape the session is shown in; the
 * browser window is resized with [fitTo] to exactly the target's shape — phone portrait, phone
 * landscape, a 1280×600 car, a 1920×720 one — so the page always fills the screen and rotating
 * the phone reflows the page instead of restarting the browser.
 *
 * Every guest process is a separate PRoot tree; [stop] reads the tree and ends all of it. The
 * browser gets SIGTERM first and a few seconds to write its profile; only then is it killed.
 */
class DesktopSession internal constructor(
    private val context: Context,
    private val runtime: LinuxRuntime,
    val adapter: DesktopBrowserAdapter,
    val screenSize: Int,
    /** Chromium's device scale factor (car/phone base × browser zoom), fixed for the process. */
    val scale: Float,
    private val restoreLastSession: Boolean,
    /** The base scale before the browser's zoom setting, so a zoom change can restart alike. */
    val baseScale: Float = scale,
    private val sensitivity: Float = 1f,
) {
    enum class Phase { STARTING, RUNNING, STOPPED, FAILED }
    data class Status(
        val phase: Phase,
        val message: String? = null,
        val startedAtMs: Long = 0,
        val firstFrameAfterMs: Long? = null,
    )

    private val layout: RuntimeLayout = runtime.layout
    private val _status = MutableStateFlow(Status(Phase.STARTING))
    val status: StateFlow<Status> = _status

    val renderer = FrameRenderer()
    private val xThread = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "desktop-x").apply { isDaemon = true } }
    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set
    @Volatile var input: BrowserInput? = null
        private set
    @Volatile var x11Connected = false
        private set
    private var x11: X11Connection? = null
    private var xvfb: Process? = null
    private var pulse: Process? = null
    @Volatile private var browser: Process? = null
    private var audio: BrowserAudioBridge? = null
    @Volatile private var stopping = false
    private var startedAt = 0L

    fun start(startUrl: String?, initialWidth: Int, initialHeight: Int) {
        width = clampSize(initialWidth)
        height = clampSize(initialHeight)
        renderer.setViewport(width, height)
        Thread({ startBlocking(startUrl) }, "desktop-session-start").start()
    }

    /**
     * Makes the browser window exactly [w]×[h] display pixels (clamped to the screen) and shows
     * that region. Cheap: one X ConfigureWindow; Chromium relayouts the page.
     */
    fun fitTo(w: Int, h: Int) {
        val nw = clampSize(w)
        val nh = clampSize(h)
        if (nw == width && nh == height) return
        width = nw
        height = nh
        renderer.setViewport(nw, nh)
        xThread.execute { resizeBrowserWindows() }
    }

    /** True when [w]×[h] does not fit this session's screen and needs a bigger one. */
    fun tooSmallFor(w: Int, h: Int): Boolean = w > screenSize || h > screenSize

    private fun clampSize(v: Int) = (v and 1.inv()).coerceIn(320, screenSize)

    /**
     * Top-level browser windows (viewable, not override-redirect menus or tooltips, bigger than a
     * dialog) are moved to 0,0 and sized to the viewport. Run on resize and periodically, so a
     * window the browser opens later (Ctrl+N, a popup) is fitted too.
     */
    private fun resizeBrowserWindows() {
        val x = x11 ?: return
        if (!x.isOpen) return
        try {
            for (id in x.queryTree()) {
                val info = x.windowInfo(id)
                if (!info.viewable || info.overrideRedirect || info.width < 200 || info.height < 150) continue
                if (info.x != 0 || info.y != 0 || info.width != width || info.height != height) {
                    x.configureWindow(id, 0, 0, width, height)
                }
            }
            x.sync()
            renderer.burst()
        } catch (e: Exception) {
            if (!stopping) DesktopLog.w("window fit failed", e)
        }
    }

    private fun startBlocking(startUrl: String?) {
        startedAt = SystemClock.elapsedRealtime()
        try {
            prepareDirectories()
            pulse = runtime.start(
                listOf("pulseaudio", "--daemonize=no", "--exit-idle-time=-1", "--disallow-exit", "-n",
                    "-F", RuntimeLayout.GUEST_PULSE_CONFIG, "--log-target=stderr", "--log-level=warning"),
                environment = mapOf("HOME" to RuntimeLayout.GUEST_HOME),
                logFile = layout.logFile("pulseaudio"),
            )
            xvfb = runtime.start(
                listOf("Xvfb", ":${RuntimeLayout.DISPLAY_NUMBER}",
                    "-screen", "0", "${screenSize}x${screenSize}x24",
                    "-fbdir", RuntimeLayout.GUEST_FB_DIR,
                    "-dpi", (scale * 96).toInt().toString(),
                    // Unix socket in private storage only: no TCP port and no abstract socket,
                    // either of which another app on the phone could connect to.
                    "-nolisten", "tcp", "-nolisten", "local", "-listen", "unix",
                    "-ac"),
                logFile = layout.logFile("xvfb"),
            )
            val connection = connectX(TimeUnit.SECONDS.toMillis(30))
            x11 = connection
            x11Connected = true
            renderer.framebuffer = openFramebuffer(TimeUnit.SECONDS.toMillis(10))
            val damage = connection.createRootDamage()
            connection.onEvent = { code, _ ->
                if (damage != null && connection.isDamageEvent(code)) {
                    runCatching { connection.subtractDamage(damage) }
                    if (_status.value.firstFrameAfterMs == null && _status.value.phase == Phase.RUNNING) {
                        _status.value = _status.value.copy(firstFrameAfterMs = SystemClock.elapsedRealtime() - startedAt)
                    }
                    renderer.requestFrame()
                }
            }
            connection.onClosed = { error -> if (!stopping) fail("X display closed", error) }
            input = BrowserInput(connection).also { it.sensitivity = sensitivity }

            browser = launchBrowser(startUrl, restoreLastSession)
            audio = BrowserAudioBridge(context, layout.pulsePcmSocket).also { it.start() }
            _status.value = Status(Phase.RUNNING, startedAtMs = System.currentTimeMillis())
            renderer.requestFrame()
            xThread.scheduleWithFixedDelay({ resizeBrowserWindows() }, 1, 3, TimeUnit.SECONDS)
            watch(browser!!)
        } catch (e: Exception) {
            fail("Could not start ${adapter.displayName}", e)
        }
    }

    /** Starts the browser on this session's display; the process is the PRoot tree's root. */
    private fun launchBrowser(startUrl: String?, restore: Boolean): Process {
        val args = adapter.launchArgs(LaunchSpec(width, height, scale, startUrl)) +
            if (restore) listOf("--restore-last-session") else emptyList()
        val pidFile = "${RuntimeLayout.GUEST_RUN_DIR}/${adapter.id.key}.pid"
        return runtime.start(
            listOf("/bin/sh", "-c", "echo \$\$ > $pidFile; exec \"\$0\" \"\$@\"", adapter.launcher) + args,
            environment = mapOf(
                "HOME" to RuntimeLayout.GUEST_HOME,
                "DISPLAY" to ":${RuntimeLayout.DISPLAY_NUMBER}",
                "PULSE_SERVER" to "unix:${RuntimeLayout.GUEST_PULSE_DIR}/native",
            ),
            logFile = layout.logFile(adapter.id.key),
            workingDirectory = RuntimeLayout.GUEST_HOME,
        )
    }

    fun detachSurface() = renderer.detach()

    /** Graceful stop: the browser saves its profile, then every process of the session goes. */
    fun stop() {
        if (stopping) return
        stopping = true
        audio?.stop()
        input?.shutdown()
        xThread.shutdownNow()
        // The browser first, with SIGTERM to its own pid so it writes its profile and session.
        val pid = readPid()
        if (pid != null) runtime.terminate(pid)
        browser?.waitFor(STOP_GRACE_S, TimeUnit.SECONDS)
        runCatching { x11?.close() }
        for (process in listOf(browser, xvfb, pulse)) runtime.kill(process)
        renderer.release()
        if (_status.value.phase != Phase.FAILED) _status.value = _status.value.copy(phase = Phase.STOPPED)
        DesktopLog.i("${adapter.id.key} session stopped")
    }

    val isAlive: Boolean get() = browser?.isAlive == true && !stopping

    fun logTail(lines: Int = 30): String = runCatching {
        layout.logFile(adapter.id.key).readLines().filterNot { it.contains("dbus/") }.takeLast(lines).joinToString("\n")
    }.getOrDefault("")

    /**
     * Follows the browser process. Closing the last tab or window makes Chrome and Brave quit
     * normally (exit 0), which used to end the whole session: a black screen with the browser's
     * log, and no way back short of leaving and reopening. A normal exit the app did not ask for
     * now starts the browser again on the same display, with a new tab, as a desktop browser
     * window would be reopened. A crash (non-zero) still fails the session, and so does a browser
     * that keeps quitting straight away ([BrowserRelaunchPolicy]), so a broken install cannot loop.
     */
    private fun watch(process: Process) {
        Thread(watcher@{
            val code = process.waitFor()
            if (stopping) return@watcher
            if (!relaunchPolicy.shouldRelaunch(code, SystemClock.elapsedRealtime())) {
                fail("${adapter.displayName} exited ($code)", null)
                return@watcher
            }
            DesktopLog.i("${adapter.id.key} closed its last window; starting it again")
            try {
                val next = launchBrowser(startUrl = null, restore = false)
                if (stopping) {
                    runtime.kill(next)
                    return@watcher
                }
                browser = next
                renderer.requestFrame()
                watch(next)
            } catch (e: Exception) {
                fail("Could not restart ${adapter.displayName}", e)
            }
        }, "desktop-browser-watch").apply { isDaemon = true; start() }
    }

    private val relaunchPolicy = BrowserRelaunchPolicy()

    private fun fail(message: String, error: Throwable?) {
        DesktopLog.w(message, error)
        val tail = logTail(6)
        _status.value = Status(Phase.FAILED, buildString {
            append(message)
            error?.let { append(": ").append(DesktopLog.describe(it)) }
            if (tail.isNotBlank()) append("\n").append(tail)
        })
        if (!stopping) Thread({ stop() }, "desktop-session-fail").start()
    }

    private fun prepareDirectories() {
        for (guest in listOf(RuntimeLayout.GUEST_FB_DIR, RuntimeLayout.GUEST_PULSE_DIR, RuntimeLayout.GUEST_RUN_DIR, RuntimeLayout.GUEST_X11_DIR)) {
            layout.host(guest).mkdirs()
        }
        // Leftovers from a session the app did not get to stop (process killed): the processes
        // died with it (--kill-on-exit), the files did not.
        layout.x11Socket.delete()
        layout.x11Lock.delete()
        layout.framebuffer.delete()
        layout.pulsePcmSocket.delete()
        File(layout.host(RuntimeLayout.GUEST_PULSE_DIR), "native").delete()
        layout.host(adapter.profileDir()).let { profile ->
            listOf("SingletonLock", "SingletonSocket", "SingletonCookie").forEach { File(profile, it).delete() }
        }
    }

    private fun connectX(timeoutMs: Long): X11Connection {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var last: Exception? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            if (xvfb?.isAlive == false) throw IOException("Xvfb exited: ${runCatching { layout.logFile("xvfb").readLines().takeLast(3).joinToString(" | ") }.getOrDefault("")}")
            if (layout.x11Socket.exists()) {
                try {
                    val socket = LocalSocket()
                    socket.connect(LocalSocketAddress(layout.x11Socket.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                    return X11Connection.open(socket.inputStream, socket.outputStream, socket)
                } catch (e: Exception) {
                    last = e
                }
            }
            Thread.sleep(200)
        }
        throw IOException("X display did not come up", last)
    }

    private fun openFramebuffer(timeoutMs: Long): XwdFramebuffer {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var last: Exception? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                return XwdFramebuffer.open(layout.framebuffer)
            } catch (e: Exception) {
                last = e
            }
            Thread.sleep(100)
        }
        throw IOException("framebuffer not available", last)
    }

    private fun readPid(): Int? = runCatching { layout.pidFile(adapter.id.key).readText().trim().toInt() }.getOrNull()

    companion object {
        private const val STOP_GRACE_S = 5L
    }
}
