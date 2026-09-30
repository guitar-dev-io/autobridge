package dev.autobridge.diagnostics

import android.content.Context
import android.os.Build
import dev.autobridge.BuildConfig
import dev.autobridge.display.StructuredLog
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-disk diagnostics that survive the process dying.
 *
 * [StructuredLog] is a 100-entry ring in memory, and both the car screens and the phone's Developer
 * Tools read that same ring because nothing in the manifest declares `android:process` — the car
 * app service and the phone Activity are one process, so it is literally one object. That works
 * while the app is alive and is worthless the moment it is not.
 *
 * The case it could not cover: a real head unit rejected a template, the host threw the
 * `IllegalArgumentException` back across the binder, and the app died as it opened a screen. The
 * ring went with it, the phone was in another car, and all that was left was Android Auto's own
 * "send feedback" sheet — which goes to Google, not to whoever is fixing the app. Nothing in the
 * app had recorded the stack trace, because nothing had ever installed an
 * [Thread.UncaughtExceptionHandler].
 *
 * So there are two files here:
 *
 * - `session.log` — every [StructuredLog] entry, appended as it happens. Covers the case where the
 *   app is killed without a Java exception (the host tearing the session down, a low-memory kill),
 *   where no handler ever runs.
 * - `crash-<millis>.txt` — written by the handler: build and device identity, the stack trace, and
 *   the whole in-memory ring as the breadcrumb trail that led to it.
 *
 * Both are capped, and the handler always chains to the previous one so the platform still gets to
 * do its normal thing (show the dialog, report to the OEM) after the report is on disk.
 */
object CrashReportStore {
    private const val DIR = "diagnostics"
    private const val SESSION_LOG = "session.log"
    private const val CRASH_PREFIX = "crash-"
    private const val CRASH_SUFFIX = ".txt"

    /** Enough to read the run-up to a crash; small enough that the share sheet still accepts it. */
    private const val MAX_SESSION_BYTES = 256 * 1024
    private const val MAX_REPORTS = 5

    private val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val lock = Any()

    @Volatile private var installed = false

    /**
     * Idempotent: called from [dev.autobridge.AutoBridgeApplication.onCreate], which runs once per
     * process for the phone UI and the car session alike.
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext

        StructuredLog.sink = { entry -> appendSession(appContext, entry) }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { writeCrash(appContext, thread, error) }
            // Always hand back to the platform. Swallowing this would leave the process in a
            // half-dead state rather than crashing, which is worse than the crash.
            previous?.uncaughtException(thread, error)
        }
        StructuredLog.i("Diagnostics", "crash reporting installed")
    }

    // ------------------------------------------------------------------ writing

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!isDirectory) mkdirs() }

    private fun appendSession(context: Context, entry: StructuredLog.Entry) {
        synchronized(lock) {
            runCatching {
                val file = File(dir(context), SESSION_LOG)
                // Restart rather than rotate: the run-up to the *current* problem is what matters,
                // and keeping a second generation doubles the disk for history nobody reads.
                if (file.length() > MAX_SESSION_BYTES) file.writeText("--- log restarted (size cap) ---\n")
                file.appendText("${entry.level.name.first()}/${entry.tag}: ${entry.message}\n")
            }
        }
    }

    private fun writeCrash(context: Context, thread: Thread, error: Throwable) {
        synchronized(lock) {
            val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            val text = buildString {
                appendLine("AutoBridge crash report")
                appendLine("time:    ${timestamp.format(Date())}")
                appendLine("version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("mode:    ${BuildConfig.AUTOBRIDGE_MODE}")
                appendLine("device:  ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("thread:  ${thread.name}")
                appendLine()
                appendLine(stack)
                appendLine("--- log leading up to it (newest last) ---")
                appendLine(StructuredLog.format(limit = 100).ifEmpty { "(no entries)" })
            }
            File(dir(context), "$CRASH_PREFIX${System.currentTimeMillis()}$CRASH_SUFFIX").writeText(text)
            prune(context)
        }
    }

    private fun prune(context: Context) {
        reportFiles(context).drop(MAX_REPORTS).forEach { runCatching { it.delete() } }
    }

    // ------------------------------------------------------------------ reading

    /** Newest first. */
    private fun reportFiles(context: Context): List<File> =
        dir(context).listFiles { f -> f.name.startsWith(CRASH_PREFIX) }
            ?.sortedByDescending { it.lastModified() }.orEmpty()

    fun reportCount(context: Context): Int = reportFiles(context).size

    /** The newest crash report, or null when the app has never crashed on this device. */
    fun latestReport(context: Context): String? =
        reportFiles(context).firstOrNull()?.let { runCatching { it.readText() }.getOrNull() }

    /** The appended session log, newest lines last. */
    fun sessionLog(context: Context): String =
        runCatching { File(dir(context), SESSION_LOG).readText() }.getOrDefault("")

    /**
     * Everything worth sending: the newest crash report if there is one, then the session log.
     * Plain text rather than a file so sharing needs no `FileProvider` and lands in any chat app.
     */
    fun shareText(context: Context): String {
        val crash = latestReport(context)
        val session = sessionLog(context).takeLast(MAX_SESSION_BYTES / 4)
        return buildString {
            if (crash != null) {
                appendLine(crash)
                appendLine()
            } else {
                appendLine("AutoBridge ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) — no crash recorded")
                appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
                appendLine()
            }
            appendLine("--- session log ---")
            append(session.ifEmpty { "(empty)" })
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            reportFiles(context).forEach { runCatching { it.delete() } }
            runCatching { File(dir(context), SESSION_LOG).delete() }
        }
    }
}
