package dev.autobridge.power

import android.content.Context

/**
 * How a car session asks the app to keep the phone's *display* awake while its *panel* is allowed
 * to go dark.
 *
 * ## Why this is a seam and not a direct call
 *
 * The policy itself lives in `:app` (`ScreenPowerController`): it owns the WakeLock, the auto-dim
 * countdown, the opt-in privileged panel-only screen-off through the Shizuku user service, and the
 * restore-on-teardown guarantee. `:duoscreen` cannot call it — the dependency runs the other way
 * (`:app` depends on `:duoscreen`, sideload flavors only) — and duplicating a hidden-API power
 * backend in the module is exactly what should not be duplicated. Both modules see `:common`, so
 * the policy is installed here once at process start and the module calls these three hooks.
 *
 * ## Why Duo Screen needs it at all
 *
 * Each pane runs on an untrusted `VirtualDisplay` (`DuoScreenDisplays`): `FLAG_TRUSTED` and
 * `FLAG_ALWAYS_UNLOCKED` need `ADD_TRUSTED_DISPLAY`, which the platform refuses an app process. So
 * when the phone actually sleeps or locks, the system stops resuming the activities in those panes
 * and the car display goes stale. Keeping the display awake — with the panel dark, when the user
 * has opted into that — is the only form of "works with the phone screen off" an app can have.
 */
interface CarScreenPowerPolicy {

    /** A session is live on the car display and owns the phone's screen power from now on. */
    fun onSessionStart(context: Context)

    /** A car-side gesture arrived: the driver is using it, so restart the idle countdown. */
    fun onCarInput()

    /** The session is over. The panel must be restored even if it was turned off privileged. */
    fun onSessionEnd()
}

/**
 * The installed policy, or nothing.
 *
 * Nothing is the normal state for a unit test and for any build without the projection flavors, so
 * every hook is a no-op until `:app` installs the real one. A session must never fail because the
 * screen policy is absent.
 */
object CarScreenPower {

    @Volatile
    private var policy: CarScreenPowerPolicy? = null

    /** Called once from `Application.onCreate`, before any car host can bind a session. */
    fun install(policy: CarScreenPowerPolicy) {
        this.policy = policy
    }

    fun sessionStarted(context: Context) {
        policy?.onSessionStart(context)
    }

    fun carInput() {
        policy?.onCarInput()
    }

    fun sessionEnded() {
        policy?.onSessionEnd()
    }
}
