package dev.autobridge.diagnostics

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import dev.autobridge.BuildConfig
import dev.autobridge.browser.BrowserSplitStore
import dev.autobridge.core.state.RuntimeContextStore
import dev.autobridge.display.MirrorDiagnostics
import dev.autobridge.input.ShizukuInputBackend
import dev.autobridge.logging.StructuredLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * "Send log": one file with what is needed to find out why something failed on the car, sent
 * through the share sheet to wherever the developer reads it.
 *
 * Car-side faults cannot be reproduced at a desk, and a description plus a photo of the head unit
 * leaves the cause to be guessed. This bundles the facts a fix depends on — version, device,
 * install source, connection and vehicle state, the switches today's features hang on — with the
 * session log [CrashReportStore] already keeps on disk, the newest crash, the mirror pipeline's
 * events, and this app's own logcat (which carries what Android, WebView and Android Auto said to
 * it). Nothing leaves the phone until the user picks where to send it.
 *
 * What it deliberately leaves out: page addresses beyond the split's side page setting (which is
 * a map by default), IPTV portal addresses and credentials, and anything from other apps. The
 * logcat is this app's process only (`--pid`), which is all an app may read anyway.
 */
object LogReport {
    private const val DIR = "reports"
    private const val LOGCAT_LINES = "3000"
    private const val LOGCAT_TIMEOUT_MS = 5_000L

    /** Builds the report file. Blocking (it reads logcat): call off the main thread. */
    fun build(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "autobridge-log-${BuildConfig.VERSION_NAME}-$stamp.txt")
        file.writeText(text(context))
        return file
    }

    private fun text(context: Context): String = buildString {
        appendLine("=== AutoBridge log report ===")
        appendLine("time:      ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        appendLine("version:   ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.AUTOBRIDGE_MODE}")
        appendLine("device:    ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("installer: ${installer(context) ?: "none"}")
        val runtime = RuntimeContextStore.context.value
        appendLine("android auto connected: ${runtime.connected}")
        appendLine("vehicle:   ${runtime.vehicleState.name}")
        appendLine("shizuku:   ${if (ShizukuInputBackend.isPermissionGranted) "granted" else "not granted"}")
        appendLine("bridge mirror listed: ${mirrorListed(context)}")
        appendLine("split (car app browser): ${BrowserSplitStore.layout(context).name}")
        appendLine("split (bridge web):      ${BrowserSplitStore.projection.layout(context).name}")
        appendLine()
        appendLine("--- newest crash ---")
        appendLine(CrashReportStore.latestReport(context) ?: "(none recorded)")
        appendLine()
        appendLine("--- mirror events ---")
        appendLine(MirrorDiagnostics.format(limit = 50).ifEmpty { "(none)" })
        appendLine()
        appendLine("--- session log (newest last) ---")
        appendLine(CrashReportStore.sessionLog(context).ifEmpty { "(empty)" })
        appendLine()
        appendLine("--- logcat, this app only (newest last) ---")
        appendLine(logcat())
    }

    /**
     * An "Send log via…" for [file]; read access is granted to the app the user picks and nothing
     * else.
     */
    fun shareIntent(context: Context, file: File, title: CharSequence): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "AutoBridge ${BuildConfig.VERSION_NAME} log")
            .putExtra(
                Intent.EXTRA_TEXT,
                "AutoBridge ${BuildConfig.VERSION_NAME} — ${Build.MANUFACTURER} ${Build.MODEL}, " +
                    "Android ${Build.VERSION.RELEASE}. Log attached."
            )
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun installer(context: Context): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
    }.getOrNull()

    /** Resolved by name: the service only exists in the sideload flavors. */
    private fun mirrorListed(context: Context): String = runCatching {
        val component = ComponentName(context.packageName, "dev.autobridge.projection.ProjectionMirrorCarService")
        when (context.packageManager.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "yes"
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> "no (switched off)"
            else -> "no (default)"
        }
    }.getOrDefault("not in this build")

    /** This process's own logcat; an app may read no other. */
    private fun logcat(): String = runCatching {
        val process = ProcessBuilder(
            "logcat", "-d", "-v", "time", "-t", LOGCAT_LINES, "--pid", android.os.Process.myPid().toString()
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(LOGCAT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) process.destroy()
        output.ifBlank { "(empty)" }
    }.getOrElse { "(unavailable: ${it.message})" }
}
