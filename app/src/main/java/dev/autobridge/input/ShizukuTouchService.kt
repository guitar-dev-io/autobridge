package dev.autobridge.input

import android.util.Log
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
 * Loaded by Shizuku into its own shell-UID process (not this app's process). Needs a public
 * no-arg constructor; Shizuku instantiates it via reflection. Shells out to the platform `input`
 * tool instead of touching hidden InputManager AIDL, since `input` is stable across OS versions.
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

    override fun destroy() {
        System.exit(0)
    }

    private fun runInput(args: List<String>): Boolean = ShizukuCommandRunner.run(args)
}
