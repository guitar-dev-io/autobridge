package dev.autobridge.duoscreen.system

import android.content.ComponentName
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import dev.autobridge.duoscreen.system.DuoScreenRootProtocol.Command
import kotlin.system.exitProcess

/**
 * The root helper: a process of its own, started by [DuoScreenRootOps] as
 * `su -c "CLASSPATH=<this apk> app_process / dev.autobridge.duoscreen.system.DuoScreenRootServer"`.
 *
 * Running as uid 0 it can do everything Shizuku's shell UID does for Duo Screen with the system
 * services' own binders, so it is [DuoScreenSystemOps] with nothing wrapped. It is started through
 * `app_process` like scrcpy's server rather than as an Android component, so none of this APK's
 * Application is bootstrapped in it (the crash [DuoScreenShizukuOps] describes cannot happen), and a
 * process not forked from the zygote as an app is not under the hidden-API restriction at all.
 *
 * Requests come in on stdin and replies go out on stdout ([DuoScreenRootProtocol]). When the app
 * goes away its end of the pipe closes, stdin reads end-of-file, and the helper exits with it.
 *
 * Kept by name in app/proguard-rules.pro: app_process finds [main] by reflection.
 */
object DuoScreenRootServer : DuoScreenSystemOps() {
    private const val TAG = "AutoBridgeDuoRootSrv"

    override val isAvailable: Boolean = true

    override fun systemBinder(serviceName: String): IBinder? =
        Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, serviceName) as? IBinder

    @JvmStatic
    fun main(args: Array<String>) {
        // Nothing here posts to a Handler, but framework code that does would need one.
        runCatching { Looper.prepareMainLooper() }
        val input = System.`in`.bufferedReader()
        val output = System.out
        output.println(DuoScreenRootProtocol.REPLY_PREFIX + DuoScreenRootProtocol.READY)
        output.flush()
        Log.i(TAG, "Root helper ready as uid ${android.os.Process.myUid()}")
        while (true) {
            val line = input.readLine() ?: break
            val result = runCatching { handle(line) }
                .onFailure { Log.w(TAG, "Request failed: ${line.take(16)}", it) }
                .getOrDefault(false)
            output.println(DuoScreenRootProtocol.reply(result))
            output.flush()
        }
        Log.i(TAG, "App closed the pipe; root helper exiting")
        exitProcess(0)
    }

    private fun handle(line: String): Boolean? = when (val command = DuoScreenRootProtocol.decode(line)) {
        null -> false
        Command.Ping -> true
        is Command.Launch -> launchOnDisplay(
            command.displayId,
            command.packageName,
            command.component?.let(ComponentName::unflattenFromString),
            command.allowSecondInstance,
        )
        is Command.Tasks -> hasTaskOnDisplay(command.displayId)
        is Command.Ime -> showImeOnDisplay(command.displayId)
        is Command.Motion -> unmarshall(command.parcel, MotionEvent.CREATOR).let { event ->
            try { injectMotion(event, command.displayId) } finally { event.recycle() }
        }
        is Command.Key -> injectKey(unmarshall(command.parcel, KeyEvent.CREATOR), command.displayId)
    }

    private fun <T : Parcelable> unmarshall(bytes: ByteArray, creator: Parcelable.Creator<T>): T {
        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            return creator.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
}
