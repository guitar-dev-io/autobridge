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
import dev.autobridge.desktop.display.SurfaceFrameRenderer
import dev.autobridge.desktop.display.X11Connection
import dev.autobridge.desktop.display.XwdFramebuffer
import dev.autobridge.desktop.input.BrowserInput
import dev.autobridge.desktop.runtime.LinuxRuntime
import dev.autobridge.desktop.runtime.RuntimeLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One running desktop browser: its own Xvfb display, PulseAudio and browser process tree, plus
 * the bridges that show it on a Surface and feed it input and audio.
 *
 * Every guest process is a separate PRoot tree started with `--kill-on-exit`, so [stop] — or the
 * app process dying — always leaves nothing running. The browser gets SIGTERM first and a few
 * seconds to write its profile; only then is its tree killed.
 */
class DesktopSession internal constructor(
    private val context: Context,
    private val runtime: LinuxRuntime,
    val adapter: DesktopBrowserAdapter,
    val width: Int,
    val height: Int,
    private val dpi: Int,
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

    val renderer = SurfaceFrameRenderer()
    @Volatile var input: BrowserInput? = null
        private set
    @Volatile var x11Connected = false
        private set
    private var x11: X11Connection? = null
    private var xvfb: Process? = null
    private var pulse: Process? = null
    private var browser: Process? = null
    private var audio: BrowserAudioBridge? = null
    @Volatile private var stopping = false
    private var startedAt = 0L

    val scale: Float get() = (dpi / 160f).coerceIn(1f, 2f)

    fun start(startUrl: String?) {
        Thread({ startBlocking(startUrl) }, "desktop-session-start").start()
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
                    "-screen", "0", "${width}x${height}x24",
                    "-fbdir", RuntimeLayout.GUEST_FB_DIR,
                    "-dpi", dpi.toString(),
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
            input = BrowserInput(connection)

            val args = adapter.launchArgs(LaunchSpec(width, height, scale, startUrl))
            val pidFile = "${RuntimeLayout.GUEST_RUN_DIR}/${adapter.id.key}.pid"
            browser = runtime.start(
                listOf("/bin/sh", "-c", "echo \$\$ > $pidFile; exec \"\$0\" \"\$@\"", adapter.launcher) + args,
                environment = mapOf(
                    "HOME" to RuntimeLayout.GUEST_HOME,
                    "DISPLAY" to ":${RuntimeLayout.DISPLAY_NUMBER}",
                    "PULSE_SERVER" to "unix:${RuntimeLayout.GUEST_PULSE_DIR}/native",
                ),
                logFile = layout.logFile(adapter.id.key),
                workingDirectory = RuntimeLayout.GUEST_HOME,
            )
            audio = BrowserAudioBridge(context, layout.pulsePcmSocket).also { it.start() }
            _status.value = Status(Phase.RUNNING, startedAtMs = System.currentTimeMillis())
            renderer.requestFrame()
            watch(browser!!)
        } catch (e: Exception) {
            fail("Could not start ${adapter.displayName}", e)
        }
    }

    fun attachSurface(surface: Surface, width: Int, height: Int) = renderer.attach(surface, width, height)
    fun setContentArea(left: Int, top: Int, right: Int, bottom: Int) = renderer.setArea(left, top, right, bottom)
    fun detachSurface() = renderer.detach()

    /** Graceful stop: the browser saves its profile, then every process of the session goes. */
    fun stop() {
        if (stopping) return
        stopping = true
        audio?.stop()
        input?.shutdown()
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

    private fun watch(process: Process) {
        Thread({
            val code = process.waitFor()
            if (!stopping) fail("${adapter.displayName} exited ($code)", null)
        }, "desktop-browser-watch").apply { isDaemon = true; start() }
    }

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
