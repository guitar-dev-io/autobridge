package dev.autobridge

import android.app.Application
import dev.autobridge.diagnostics.CrashReportStore

/**
 * Exists for one reason: something has to run before the first screen, in whichever entry point
 * starts the process.
 *
 * The app has three of those — the phone Activity, the Android Auto `CarAppService`, and the
 * projection service — and a crash on the car has to be recorded whichever one came first. An
 * `Application` is the only place that is true, so [CrashReportStore.install] goes here rather than
 * in any Activity's `onCreate`.
 */
class AutoBridgeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReportStore.install(this)
    }
}
