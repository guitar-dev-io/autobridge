package dev.autobridge.input

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy
import rikka.shizuku.Shizuku

/**
 * Privileged touch backend bound through a Shizuku user service. It reports its narrower gesture
 * set so PINCH can fall back to Accessibility rather than failing silently.
 */
object ShizukuInputBackend : InputBackend {
    private const val TAG = "AutoBridgeShizuku"
    const val REQUEST_CODE = 9100
    private const val SERVICE_VERSION = 1

    @Volatile
    private var remote: IShizukuTouchService? = null
    @Volatile
    private var remoteBinder: IBinder? = null

    private val deathRecipient = IBinder.DeathRecipient {
        remote = null
        remoteBinder = null
        Log.w(TAG, "Shizuku touch service binder died")
    }

    override val capabilities: Set<InputCapability> = setOf(
        InputCapability.TAP,
        InputCapability.LONG_PRESS,
        InputCapability.SWIPE,
        InputCapability.SCROLL,
        InputCapability.FLING,
        InputCapability.BACK,
        InputCapability.HOME,
        InputCapability.RECENTS
    )

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
            Log.i(TAG, "Shizuku touch service connected: ${remote != null}")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            clearRemote("service disconnected")
        }
    }

    val isPermissionGranted: Boolean
        get() = Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    override val isAvailable: Boolean
        get() = FeaturePolicy.app.isAvailable(Feature.TOUCH) &&
            FeaturePolicy.app.isAvailable(Feature.SHIZUKU) &&
            isPermissionGranted && remote != null

    fun requestPermission() {
        if (Shizuku.pingBinder() && !isPermissionGranted) Shizuku.requestPermission(REQUEST_CODE)
    }

    fun bind(context: Context) {
        if (!isPermissionGranted || remote != null) return
        runCatching { Shizuku.bindUserService(userServiceArgs(context), connection) }
            .onFailure { Log.w(TAG, "bindUserService failed", it) }
    }

    fun unbind(context: Context) {
        clearRemote("explicit unbind")
        runCatching { Shizuku.unbindUserService(userServiceArgs(context), connection, true) }
            .onFailure { Log.w(TAG, "unbindUserService failed", it) }
    }

    private fun clearRemote(reason: String) {
        val binder = remoteBinder
        remote = null
        remoteBinder = null
        if (binder != null) runCatching { binder.unlinkToDeath(deathRecipient, 0) }
        Log.d(TAG, "Shizuku touch remote cleared: $reason")
    }

    private fun allowed(): Boolean = FeaturePolicy.app.isAvailable(Feature.TOUCH)

    private inline fun invokeRemote(operation: String, call: (IShizukuTouchService) -> Boolean): Boolean {
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
