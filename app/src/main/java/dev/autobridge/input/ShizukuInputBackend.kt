package dev.autobridge.input

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.shizuku.ShizukuGrant
import rikka.shizuku.Shizuku

/**
 * Privileged backend bound through a Shizuku user service. Basic shell `input` gestures remain
 * separate from the optional panel-power and real pointer capabilities, which are reported only
 * after the remote service probes its hidden APIs successfully.
 */
object ShizukuInputBackend : InputBackend {
    private const val TAG = "AutoBridgeShizuku"
    const val REQUEST_CODE = 9100
    private const val SERVICE_VERSION = 2

    @Volatile
    private var remote: IShizukuTouchService? = null
    @Volatile
    private var remoteBinder: IBinder? = null

    private val deathRecipient = IBinder.DeathRecipient {
        remote = null
        remoteBinder = null
        Log.w(TAG, "Shizuku touch service binder died")
    }

    private val basicCapabilities = setOf(
        InputCapability.TAP,
        InputCapability.LONG_PRESS,
        InputCapability.SWIPE,
        InputCapability.SCROLL,
        InputCapability.FLING,
        InputCapability.BACK,
        InputCapability.HOME,
        InputCapability.RECENTS
    )

    override val capabilities: Set<InputCapability>
        get() = buildSet {
            addAll(basicCapabilities)
            if (isRealTouchAvailable) add(InputCapability.REAL_TOUCH)
        }

    private fun userServiceArgs(context: Context) = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShizukuTouchService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("touch")
        .debuggable(false)
        .version(SERVICE_VERSION)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            clearRemote("replaced by a new connection")
            if (!binder.pingBinder()) {
                Log.w(TAG, "Shizuku touch service binder is not alive")
                return
            }
            runCatching { binder.linkToDeath(deathRecipient, 0) }
                .onFailure {
                    Log.w(TAG, "Could not monitor Shizuku touch binder", it)
                    return@onFailure
                }
            remoteBinder = binder
            remote = IShizukuTouchService.Stub.asInterface(binder)
            Log.i(TAG, "Shizuku privileged service connected: ${remote != null}")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            clearRemote("service disconnected")
        }
    }

    /** Shared with Duo Screen, which gates its privileged ops on the very same grant. */
    val isPermissionGranted: Boolean
        get() = ShizukuGrant.isGranted

    override val isAvailable: Boolean
        get() = FeaturePolicy.app.isAvailable(Feature.TOUCH) &&
            FeaturePolicy.app.isAvailable(Feature.SHIZUKU) &&
            isPermissionGranted && remote != null

    val isPanelPowerAvailable: Boolean
        get() = remote != null && invokeRemote("display power capability") {
            it.isDisplayPowerControlAvailable()
        }

    val isRealTouchAvailable: Boolean
        get() = remote != null && invokeRemote("real touch capability") {
            it.isRealTouchAvailable()
        }

    fun requestPermission() {
        if (Shizuku.pingBinder() && !isPermissionGranted) Shizuku.requestPermission(REQUEST_CODE)
    }

    fun bind(context: Context) {
        if (!isPermissionGranted || remote != null) return
        runCatching { Shizuku.bindUserService(userServiceArgs(context), connection) }
            .onFailure { Log.w(TAG, "bindUserService failed", it) }
    }

    fun unbind(context: Context) {
        // Cleanup happens before dropping the binder so a service teardown cannot leave the panel
        // off or a pointer stream held down.
        restorePanelPower()
        cancelRealTouch()
        clearRemote("explicit unbind")
        runCatching { Shizuku.unbindUserService(userServiceArgs(context), connection, true) }
            .onFailure { Log.w(TAG, "unbindUserService failed", it) }
    }

    fun setPanelPower(on: Boolean): Boolean {
        if (!isPermissionGranted || !FeaturePolicy.app.isAvailable(Feature.SHIZUKU)) return false
        return invokeRemote("set panel power=$on") { it.setDisplayPower(on) }
    }

    /** Cleanup operation intentionally bypasses the normal TOUCH policy gate. */
    fun restorePanelPower(): Boolean = invokeRemote("restore panel power") {
        it.setDisplayPower(true)
    }

    fun cancelRealTouch(): Boolean = invokeRemote("cancel real touch") {
        it.touchCancel()
    }

    /**
     * Runs a bounded shell command in the Shizuku shell-UID process, returning its combined output
     * or null. Bypasses the TOUCH feature gate: this is used for installation setup, not input
     * injection, and must work before any touch capability is relevant.
     */
    fun runShellCommand(args: List<String>, timeoutMs: Long = 8_000L): String? {
        val service = remote ?: return null
        return runCatching { service.runShellCommand(args.toTypedArray(), timeoutMs) }
            .onFailure {
                clearRemote("runShellCommand failed")
                Log.w(TAG, "Shizuku runShellCommand failed", it)
            }
            .getOrNull()
    }

    private fun clearRemote(reason: String) {
        val binder = remoteBinder
        remote = null
        remoteBinder = null
        if (binder != null) runCatching { binder.unlinkToDeath(deathRecipient, 0) }
        Log.d(TAG, "Shizuku privileged remote cleared: $reason")
    }

    private fun allowed(): Boolean = FeaturePolicy.app.isAvailable(Feature.TOUCH)

    private inline fun invokeRemote(
        operation: String,
        call: (IShizukuTouchService) -> Boolean
    ): Boolean {
        val service = remote ?: return false
        return runCatching { call(service) }.onFailure {
            clearRemote("$operation failed")
            Log.w(TAG, "Shizuku $operation failed", it)
        }.getOrDefault(false)
    }

    override suspend fun tap(x: Float, y: Float): Boolean {
        if (!allowed()) return false
        return invokeRemote("tap") { it.tap(x.toInt(), y.toInt()) }
    }

    override suspend fun swipe(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long
    ): Boolean {
        if (!allowed()) return false
        return invokeRemote("swipe") {
            it.swipe(fromX.toInt(), fromY.toInt(), toX.toInt(), toY.toInt(), durationMs)
        }
    }

    override suspend fun longPress(x: Float, y: Float, durationMs: Long): Boolean =
        swipe(x, y, x, y, durationMs)

    override suspend fun touchDown(pointerId: Int, x: Float, y: Float): Boolean {
        if (!allowed() || !isRealTouchAvailable) return false
        return invokeRemote("touch down") { it.touchDown(pointerId, x.toInt(), y.toInt()) }
    }

    override suspend fun touchMove(pointerIds: IntArray, xs: FloatArray, ys: FloatArray): Boolean {
        if (!allowed() || !isRealTouchAvailable ||
            pointerIds.size != xs.size || pointerIds.size != ys.size
        ) return false
        return invokeRemote("touch move") {
            it.touchMove(
                pointerIds,
                xs.map(Float::toInt).toIntArray(),
                ys.map(Float::toInt).toIntArray()
            )
        }
    }

    override suspend fun touchUp(pointerId: Int, x: Float, y: Float): Boolean {
        if (!allowed() || !isRealTouchAvailable) return false
        return invokeRemote("touch up") { it.touchUp(pointerId, x.toInt(), y.toInt()) }
    }

    override suspend fun touchCancel(): Boolean = cancelRealTouch()

    override suspend fun back(): Boolean = keyevent(4)
    override suspend fun home(): Boolean = keyevent(3)
    override suspend fun recentApps(): Boolean = keyevent(187)

    override suspend fun systemAction(action: InputBackend.SystemAction): Boolean = when (action) {
        InputBackend.SystemAction.BACK -> back()
        InputBackend.SystemAction.HOME -> home()
        InputBackend.SystemAction.RECENTS -> recentApps()
    }

    private suspend fun keyevent(keyCode: Int): Boolean {
        if (!allowed()) return false
        return invokeRemote("keyevent") { it.keyevent(keyCode) }
    }
}
