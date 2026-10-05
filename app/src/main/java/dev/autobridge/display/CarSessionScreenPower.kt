package dev.autobridge.display

import android.content.Context
import dev.autobridge.power.CarScreenPower
import dev.autobridge.power.CarScreenPowerPolicy

/**
 * The app's answer to [CarScreenPowerPolicy]: hand a car session's screen-power hooks to
 * [ScreenPowerController], which already owns the WakeLock, the auto-dim countdown, the opt-in
 * privileged panel-only screen-off and the restore-on-teardown guarantee.
 *
 * This is the whole of the `:app` side. The seam exists because `:duoscreen` cannot see this class
 * (see [CarScreenPowerPolicy]); nothing else about the policy moves.
 */
object CarSessionScreenPower : CarScreenPowerPolicy {

    /** Installed from `AutoBridgeApplication`, before any car host can bind a session. */
    fun install() {
        CarScreenPower.install(this)
    }

    override fun onSessionStart(context: Context) {
        ScreenPowerController.startForCarSession(context)
    }

    override fun onCarInput() {
        ScreenPowerController.userActivity()
    }

    override fun onSessionEnd() {
        ScreenPowerController.stopCarSession()
    }
}
