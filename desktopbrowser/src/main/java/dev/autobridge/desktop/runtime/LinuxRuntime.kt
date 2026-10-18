package dev.autobridge.desktop.runtime

import android.os.Process as AndroidProcess
import dev.autobridge.desktop.DesktopLog
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Starts glibc programs from the Debian rootfs under PRoot, as ordinary child processes of this
 * app (same uid, same SELinux domain: nothing here asks for privileges).
 *
 * Android forbids an app targeting API 29+ from `exec`-ing files it can write. PRoot and its
 * loader therefore live in `nativeLibraryDir` (installed read-only from the APK), and every guest
 * program is mapped by that loader rather than exec'd by the kernel.
 */
class LinuxRuntime(val layout: RuntimeLayout) {
    /** Settable from diagnostics for kernels whose seccomp support misbehaves under PRoot. */
    @Volatile var seccompAcceleration: Boolean = true

    fun binariesPresent(): Boolean = layout.proot.canExecute() && layout.prootLoader.isFile

    fun rootfsReady(): Boolean = layout.completeMarker.isFile

    fun command(
        command: List<String>,
        environment: Map<String, String> = emptyMap(),
        workingDirectory: String = "/root",
    ): ProotCommand = ProotCommand.build(
        prootPath = layout.proot.absolutePath,
        loaderPath = layout.prootLoader.absolutePath,
        prootTmpDir = layout.prootTmp.absolutePath,
        rootfsPath = layout.rootfs.absolutePath,
        command = command,
        guestEnvironment = environment,
        workingDirectory = workingDirectory,
        fakeProcFiles = fakeProcBindings(),
        seccompAcceleration = seccompAcceleration,
    )

    /** Starts a long-running guest process; its stdout+stderr go to [logFile]. */
    fun start(
        command: List<String>,
        environment: Map<String, String> = emptyMap(),
        logFile: File,
        workingDirectory: String = "/root",
    ): Process {
        check(binariesPresent()) { "PRoot is not installed for this ABI" }
        layout.prootTmp.mkdirs()
        logFile.parentFile?.mkdirs()
        rotate(logFile)
        val built = command(command, environment, workingDirectory)
        DesktopLog.i("start: ${command.joinToString(" ").take(300)}")
        return ProcessBuilder(built.argv)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .also { it.environment().putAll(built.environment) }
            .directory(layout.base)
            .start()
    }

    /**
     * Runs a guest command to completion, handing each output line to [onLine]. Returns the exit
     * code; [isCancelled] is polled while it runs and kills the command when it turns true.
     */
    fun run(
        command: List<String>,
        environment: Map<String, String> = emptyMap(),
        logFile: File? = null,
        timeoutMs: Long = TimeUnit.HOURS.toMillis(1),
        isCancelled: () -> Boolean = { false },
        onLine: (String) -> Unit = {},
    ): Int {
        check(binariesPresent()) { "PRoot is not installed for this ABI" }
        layout.prootTmp.mkdirs()
        val built = command(command, environment)
        DesktopLog.i("run: ${command.joinToString(" ").take(300)}")
        val process = ProcessBuilder(built.argv)
            .redirectErrorStream(true)
            .also { it.environment().putAll(built.environment) }
            .directory(layout.base)
            .start()
        val log = logFile?.let { it.parentFile?.mkdirs(); java.io.BufferedWriter(java.io.FileWriter(it, true)) }
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    log?.apply { write(line); newLine(); flush() }
                    onLine(line)
                }
            } catch (_: IOException) {
                // Stream closed because the process was killed.
            }
        }.apply { name = "desktop-run-output"; isDaemon = true; start() }
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                if (isCancelled()) {
                    process.destroy()
                    if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
                    throw CancelledException()
                }
                if (System.currentTimeMillis() > deadline) {
                    process.destroyForcibly()
                    throw IOException("timed out: ${command.firstOrNull()}")
                }
            }
            reader.join(2000)
            return process.exitValue()
        } finally {
            log?.close()
        }
    }

    /** Runs a command and returns its output (trimmed); throws on a non-zero exit. */
    fun output(command: List<String>, environment: Map<String, String> = emptyMap(), timeoutMs: Long = 60_000): String {
        val lines = ArrayList<String>()
        val code = run(command, environment, timeoutMs = timeoutMs) { lines += it }
        if (code != 0) throw IOException("${command.first()} exited $code: ${lines.takeLast(5).joinToString(" | ")}")
        return lines.joinToString("\n").trim()
    }

    class CancelledException : IOException("cancelled")

    /** SIGTERM to a guest pid (guest pids are real pids: PRoot does not namespace them). */
    fun terminate(pid: Int) {
        runCatching { AndroidProcess.sendSignal(pid, SIGTERM) }
    }

    fun isAlive(pid: Int): Boolean = File("/proc/$pid").exists()

    /**
     * Ends a PRoot tree for certain: every guest process, then PRoot itself.
     *
     * Neither `Process.destroy()` nor Android's `destroyForcibly()` is enough. Both send SIGTERM,
     * which PRoot ignores while tracing, and `--kill-on-exit` only runs when PRoot exits on its
     * own, not when it is killed. Both were observed on the emulator: Xvfb and PulseAudio
     * survived a stop. So the tree is read from /proc *before* anything dies (orphans would be
     * re-parented and lost), sent SIGTERM, and whatever is left gets SIGKILL.
     */
    fun kill(process: Process?, graceMs: Long = 1500) {
        if (process == null || !process.isAlive) return
        val root = pidOf(process)
        val tree = if (root != null) descendants(root) else emptyList()
        tree.forEach { runCatching { AndroidProcess.sendSignal(it, SIGTERM) } }
        process.destroy()
        val deadline = System.currentTimeMillis() + graceMs
        while (System.currentTimeMillis() < deadline && (process.isAlive || tree.any(::isAlive))) Thread.sleep(50)
        (tree.filter(::isAlive) + listOfNotNull(root?.takeIf { process.isAlive })).forEach {
            runCatching { AndroidProcess.sendSignal(it, SIGKILL) }
        }
        process.waitFor(2, TimeUnit.SECONDS)
        val left = tree.filter(::isAlive)
        if (left.isNotEmpty()) DesktopLog.w("processes still alive after stop: $left")
    }

    /** All processes below [pid], from /proc/<n>/stat (ppid is the 4th field, after "(comm)"). */
    fun descendants(pid: Int): List<Int> {
        val parent = HashMap<Int, Int>()
        File("/proc").listFiles()?.forEach { dir ->
            val n = dir.name.toIntOrNull() ?: return@forEach
            val stat = runCatching { File(dir, "stat").readText() }.getOrNull() ?: return@forEach
            val fields = stat.substringAfterLast(')').trim().split(' ')
            fields.getOrNull(1)?.toIntOrNull()?.let { parent[n] = it }
        }
        val out = ArrayList<Int>()
        var frontier = listOf(pid)
        while (frontier.isNotEmpty()) {
            val next = parent.filter { it.value in frontier }.keys.toList()
            out += next
            frontier = next
        }
        return out
    }

    /** Android's Process has no pid() API; its implementation class keeps the pid in a field. */
    private fun pidOf(process: Process): Int? = runCatching {
        val field = process.javaClass.getDeclaredField("pid").apply { isAccessible = true }
        field.getInt(process)
    }.getOrNull() ?: Regex("pid=(\\d+)").find(process.toString())?.groupValues?.get(1)?.toIntOrNull()

    /**
     * `/proc` entries Android hides from apps. Each gets a static stand-in, written once, and is
     * bound over the real path only when this process really cannot read it.
     */
    private fun fakeProcBindings(): Map<String, String> {
        val dir = File(layout.rootfs, ".autobridge-proc")
        val out = HashMap<String, String>()
        for ((name, content) in FAKE_PROC) {
            val readable = runCatching { File("/proc/$name").readBytes(); true }.getOrDefault(false)
            if (readable) continue
            val file = File(dir, name.replace('/', '_'))
            if (!file.isFile) {
                dir.mkdirs()
                file.writeText(content)
            }
            out["/proc/$name"] = file.absolutePath
        }
        return out
    }

    private fun rotate(logFile: File) {
        if (logFile.length() > MAX_LOG_BYTES) {
            val old = File(logFile.path + ".1")
            old.delete()
            logFile.renameTo(old)
        }
    }

    companion object {
        private const val SIGTERM = 15
        private const val SIGKILL = 9
        private const val MAX_LOG_BYTES = 2L * 1024 * 1024

        // Same shapes proot-distro uses; values are plausible and static.
        private val FAKE_PROC = mapOf(
            "stat" to "cpu  1957 0 2877 93280 262 342 254 87 0 0\ncpu0 31 0 226 12027 82 10 4 9 0 0\n" +
                "intr 63361 0 0\nctxt 38014093\nbtime 1694292441\nprocesses 26442\nprocs_running 1\nprocs_blocked 0\n" +
                "softirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677\n",
            "loadavg" to "0.12 0.07 0.02 2/165 765\n",
            "uptime" to "124.08 932.80\n",
            "vmstat" to "nr_free_pages 1743136\nnr_zone_inactive_anon 179281\nnr_zone_active_anon 7183\n",
            "version" to "Linux version 6.1.0-autobridge (proot) #1 SMP PREEMPT\n",
        )
    }
}
