package dev.autobridge.duoscreen.system

import android.os.IBinder
import dev.autobridge.duoscreen.DuoLog
import dev.autobridge.shizuku.ShizukuGrant
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Privileged ops over Shizuku's *binder* path rather than its user-service path.
 *
 * [rikka.shizuku.Shizuku.bindUserService] spawns a shell-UID process and bootstraps this APK's
 * Application inside it, which crashes on this device's ROM (Xiaomi/MediaTek + Android 16) deep
 * inside `LoadedApk.makeApplicationInner` — a known Shizuku issue, reproduced here for the
 * already-shipped ShizukuTouchService too, so it is not specific to Duo Screen.
 * [ShizukuBinderWrapper] instead forwards a single binder transaction through the Shizuku server so
 * it executes as the shell UID, loading nothing of ours into another process, which sidesteps that
 * crash entirely.
 */
object DuoScreenShizukuOps : DuoScreenSystemOps() {
    private const val TAG = "AutoBridgeDuoOps"

    /** The one Shizuku grant the app holds; Duo Screen never asks for a second one. */
    override val isAvailable: Boolean
        get() = ShizukuGrant.isGranted

    /**
     * Both Stub.asInterface methods are hidden-API blocked at this target SDK ("api=blocked" for
     * IActivityTaskManager, "max-target-r" for IActivityManager), so plain reflection is denied.
     * The empty prefix exempts everything, which is what reaching an arbitrary system interface
     * needs; done once, lazily, so a flavor without the library simply reports unavailable.
     */
    private val exempted: Boolean by lazy {
        runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
            .onFailure { DuoLog.w(TAG, "Could not lift the hidden-API restriction", it) }
            .getOrDefault(false)
    }

    override val hiddenApiExempted: Boolean
        get() = exempted

    /** Wraps a system service binder so its transactions run as the shell UID. */
    override fun systemBinder(serviceName: String): IBinder? =
        SystemServiceHelper.getSystemService(serviceName)?.let(::ShizukuBinderWrapper)
}
