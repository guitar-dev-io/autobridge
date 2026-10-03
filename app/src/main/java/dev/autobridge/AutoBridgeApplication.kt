package dev.autobridge

import android.app.Application
import android.content.Context
import dev.autobridge.diagnostics.CrashReportStore
import dev.autobridge.i18n.AppLocale
import dev.autobridge.safety.BypassNotifier
import dev.autobridge.safety.BypassPolicyStore

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

    /**
     * Applies the Settings &gt; Language choice to the *application* context, so the strings read
     * outside an activity — the projection notification, the car screens, anything holding
     * `applicationContext` — follow it too. Each activity re-bases its own context as well; this
     * covers everything that is not one. A no-op on API 33+, where the platform resolves the
     * locale itself. See [AppLocale.rebase].
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.rebase(base))
    }

    override fun onCreate() {
        super.onCreate()
        CrashReportStore.install(this)
        // Load the persisted safety-bypass flag before any screen or policy read, and start the
        // notification that reflects it.
        BypassPolicyStore.init(this)
        BypassNotifier.install(this)
    }
}
