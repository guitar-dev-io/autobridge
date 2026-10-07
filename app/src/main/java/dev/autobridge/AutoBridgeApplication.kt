package dev.autobridge

import android.app.Application
import android.content.Context
import dev.autobridge.diagnostics.CrashReportStore
import dev.autobridge.display.CarSessionScreenPower
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
        // A car session (Duo Screen) can start without any screen of ours having run, so the screen
        // power policy has to be installed here rather than in an Activity. Registration only; it
        // takes no lock and reads no settings until a session starts.
        CarSessionScreenPower.install()
        // The phone's "connected to the car" state follows Android Auto itself, not only the one
        // car screen session that used to set it; see CarConnectionMonitor.
        dev.autobridge.car.CarConnectionMonitor.install(this)
    }

    /**
     * A loaded Whisper model is the largest thing this process holds that it can rebuild on demand,
     * so it is the first thing given back under memory pressure; see [dev.autobridge.voice.VoiceRuntime].
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        dev.autobridge.voice.VoiceRuntime.onTrimMemory(level)
    }
}
