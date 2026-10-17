package dev.autobridge.duoscreen.system

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.Parcelable
import android.view.KeyEvent
import android.view.MotionEvent
import dev.autobridge.duoscreen.DuoLog
import dev.autobridge.duoscreen.system.DuoScreenRootProtocol.Command
import java.io.BufferedWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Duo Screen's privileged ops without Shizuku: forwarded to [DuoScreenRootServer], a helper this
 * app starts with `su` and talks to over its stdin/stdout ([DuoScreenRootProtocol]).
 *
 * Covers the launches, touch, keys, the task query and the keyboard placement. What it cannot do
 * is create a *trusted* pane display: that call carries the car's Surface, a binder object, and a
 * `su` pipe carries bytes. So on root alone the panes are this app's own displays, which work while
 * the phone is awake and stop when its power button puts it to sleep; [DuoScreenOps] still takes
 * the trusted displays from Shizuku whenever Shizuku is granted too.
 *
 * Starting the helper is what asks the root manager (Magisk, KernelSU, APatch) for permission, and
 * that prompt waits for the user, so [start] blocks and must stay off the main thread; [startAsync]
 * is the way in from UI code.
 */
object DuoScreenRootOps : DuoScreenPrivilegedOps {
    private const val TAG = "AutoBridgeDuoRoot"

    /** The root manager's prompt waits for a tap; most give up on their own after about 10 s. */
    private const val START_TIMEOUT_MS = 20_000L

    /**
     * How long one request may take before the helper is presumed wedged. Under the five seconds
     * that would ANR the main thread, which a pane launch is made from.
     */
    private const val REPLY_TIMEOUT_MS = 3_000L

    private class Session(
        val process: Process,
        val writer: BufferedWriter,
        val replies: LinkedBlockingQueue<String>,
    )

    @Volatile
    private var session: Session? = null

    /** One request in flight at a time: replies carry no id and come back in order. */
    private val requestLock = Any()
    private val startLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    override val isAvailable: Boolean
        get() = session?.process?.isAlive == true

    /**
     * Starts the helper unless it is already running, and returns whether it is. Blocks for as
     * long as the root manager's prompt is up; never call it on the main thread.
     */
    fun start(context: Context): Boolean = synchronized(startLock) {
        if (isAvailable) return true
        val apk = context.applicationInfo.sourceDir
        val command = "CLASSPATH='$apk' exec /system/bin/app_process /system/bin ${DuoScreenRootServer::class.java.name}"
        val process = runCatching { ProcessBuilder("su", "-c", command).start() }.getOrElse { error ->
            DuoLog.w(TAG, "Could not run su; the phone is not rooted, or su is not on the PATH", error)
            return false
        }
        val replies = LinkedBlockingQueue<String>()
        thread(name = "DuoRootOut", isDaemon = true) {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    val body = DuoScreenRootProtocol.replyBody(line)
                    if (body != null) replies.put(body) else DuoLog.i(TAG, "helper: $line")
                }
            }
            DuoLog.i(TAG, "Root helper stdout closed")
        }
        thread(name = "DuoRootErr", isDaemon = true) {
            runCatching { process.errorStream.bufferedReader().forEachLine { DuoLog.w(TAG, "helper: $it") } }
        }
        val first = replies.poll(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (first != DuoScreenRootProtocol.READY) {
            DuoLog.w(TAG, "Root helper did not start (${first ?: "no answer"}); root refused or unavailable")
            process.destroy()
            return false
        }
        session = Session(process, process.outputStream.bufferedWriter(), replies)
        DuoLog.i(TAG, "Root helper running")
        return true
    }

    /** [start] on a worker thread; [onDone] gets the outcome on the main thread. */
    fun startAsync(context: Context, onDone: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        thread(name = "DuoRootStart", isDaemon = true) {
            val started = start(app)
            mainHandler.post { onDone(started) }
        }
    }

    /** Ends the helper; closing its stdin is enough, destroy() is for one that does not listen. */
    fun stop() {
        val ended = synchronized(requestLock) { session.also { session = null } } ?: return
        runCatching { ended.writer.close() }
        ended.process.destroy()
        DuoLog.i(TAG, "Root helper stopped")
    }

    override fun launchOnDisplay(
        displayId: Int,
        packageName: String,
        component: ComponentName?,
        allowSecondInstance: Boolean,
    ): Boolean = request(
        Command.Launch(displayId, packageName, component?.flattenToString(), allowSecondInstance)
    ) == true

    override fun hasTaskOnDisplay(displayId: Int): Boolean? = request(Command.Tasks(displayId))

    override fun showImeOnDisplay(displayId: Int): Boolean = request(Command.Ime(displayId)) == true

    override fun injectMotion(event: MotionEvent, displayId: Int): Boolean =
        request(Command.Motion(displayId, marshall(event))) == true

    override fun injectKey(event: KeyEvent, displayId: Int): Boolean =
        request(Command.Key(displayId, marshall(event))) == true

    /** Sends [command] and waits for its reply; null when the helper is gone or does not answer. */
    private fun request(command: Command): Boolean? = synchronized(requestLock) {
        val active = session ?: return null
        if (!active.process.isAlive) {
            session = null
            DuoLog.w(TAG, "Root helper has exited")
            return null
        }
        active.replies.clear()
        val reply = runCatching {
            active.writer.write(DuoScreenRootProtocol.encode(command))
            active.writer.newLine()
            active.writer.flush()
            active.replies.poll(REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrNull()
        if (reply == null) {
            // A late reply would be read as the answer to the next request, so a helper that
            // missed one is not trusted again; the next session start brings up a fresh one.
            DuoLog.w(TAG, "Root helper did not answer ${command.javaClass.simpleName}; stopping it")
            session = null
            active.process.destroy()
            return null
        }
        DuoScreenRootProtocol.parseResult(reply)
    }

    private fun marshall(event: Parcelable): ByteArray {
        val parcel = Parcel.obtain()
        try {
            event.writeToParcel(parcel, 0)
            return parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }
}
