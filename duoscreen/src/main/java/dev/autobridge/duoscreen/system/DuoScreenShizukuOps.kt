package dev.autobridge.duoscreen.system

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.MotionEvent
import dev.autobridge.shizuku.ShizukuGrant
import java.lang.reflect.Method
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
 *
 * Both system interfaces are reached reflectively because they are hidden APIs whose signatures
 * move between releases; every lookup probes and reports failure rather than assuming support, the
 * same approach [dev.autobridge.input.ShizukuDisplayPowerController] takes.
 */
object DuoScreenShizukuOps : DuoScreenPrivilegedOps {
    private const val TAG = "AutoBridgeDuoOps"
    /**
     * Fire-and-forget, not WAIT_FOR_FINISH (2).
     *
     * WAIT_FOR_FINISH blocks the caller until the app that receives the event has finished
     * handling it. The call that gets here starts at the car host's onClick/onScroll, which the
     * car app library dispatches on the main thread, so waiting meant the phone's whole UI was
     * frozen for as long as a pane's app took to answer — and a scroll is six of these in a row.
     * Android calls that at five seconds: "Input dispatching timed out".
     *
     * Nothing here needs the finish signal. The boolean the caller uses is "was the event
     * accepted", which ASYNC returns just the same.
     */
    private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0

    /**
     * The launch is attributed to the shell package, not ours: the transaction arrives from uid
     * 2000, and the system rejects a calling package that does not belong to the calling uid. This
     * is what `adb shell am start` itself passes.
     */
    private const val SHELL_PACKAGE = "com.android.shell"

    private data class ActivityApi(val service: Any, val start: Method)
    private data class InputApi(val service: Any, val inject: Method)

    @Volatile
    private var activityApi: ActivityApi? = null

    @Volatile
    private var inputApi: InputApi? = null

    /**
     * Both Stub.asInterface methods are hidden-API blocked at this target SDK ("api=blocked" for
     * IActivityTaskManager, "max-target-r" for IActivityManager), so plain reflection is denied.
     * The empty prefix exempts everything, which is what reaching an arbitrary system interface
     * needs; done once, lazily, so a flavor without the library simply reports unavailable.
     */
    private val hiddenApiExempted: Boolean by lazy {
        runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
            .onFailure { Log.w(TAG, "Could not lift the hidden-API restriction", it) }
            .getOrDefault(false)
    }

    /** The one Shizuku grant the app holds; Duo Screen never asks for a second one. */
    override val isAvailable: Boolean
        get() = ShizukuGrant.isGranted

    fun reset() {
        activityApi = null
        inputApi = null
    }

    override fun launchOnDisplay(
        displayId: Int,
        packageName: String,
        component: ComponentName?,
        allowSecondInstance: Boolean,
    ): Boolean {
        if (!isAvailable) return false
        // Prefer an explicit component when the caller resolved one. The caller
        // ([dev.autobridge.duoscreen.DuoScreenController]) resolves the launcher activity in-app under the
        // QUERY_ALL_PACKAGES the personal/lab manifest already declares (app/src/projection/
        // AndroidManifest.xml) — the same visibility the pane picker already uses — because an
        // *implicit* MAIN/LAUNCHER + setPackage launch fails to resolve on this device's MIUI
        // build for a package whose launcher activity is only "enabled by default", leaving a
        // blank pane. The implicit intent below stays as the fallback for any package the caller
        // could not resolve, so the shell transaction still resolves it with shell's visibility
        // and nothing that worked before regresses. The launch itself is unchanged either way:
        // startActivityAsUser still runs as com.android.shell on the target display id.
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (allowSecondInstance) intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        if (component != null) intent.component = component else intent.setPackage(packageName)
        val api = resolveActivityApi() ?: return false
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val args = buildStartActivityArgs(api.start, intent, options)
        return runCatching {
            val result = api.start.invoke(api.service, *args) as? Int ?: -1
            // ActivityManager.START_* : anything >= 0 started or was already running.
            val started = result >= 0
            if (!started) Log.w(TAG, "startActivityAsUser($packageName -> display $displayId) returned $result")
            started
        }.onFailure { error ->
            activityApi = null
            Log.w(TAG, "startActivityAsUser failed for $packageName on display $displayId", error)
        }.getOrDefault(false)
    }

    override fun injectMotion(event: MotionEvent, displayId: Int): Boolean {
        if (!isAvailable) return false
        val api = resolveInputApi() ?: return false
        if (!setDisplayId(event, displayId)) return false
        return runCatching {
            val args = buildInjectArgs(api.inject, event)
            api.inject.invoke(api.service, *args) as? Boolean ?: false
        }.onFailure { error ->
            inputApi = null
            Log.w(TAG, "injectInputEvent failed on display $displayId", error)
        }.getOrDefault(false)
    }

    /**
     * Fills [method]'s parameters positionally by type: the intent, the launch options, the shell
     * package for the first String (callingPackage) and null for the rest (callingFeatureId,
     * resolvedType, resultWho), 0 for every int (requestCode, flags, and userId 0 = the current
     * user), and null for the binder/IApplicationThread/ProfilerInfo slots. Done by type rather
     * than by a fixed signature because the parameter list has gained entries across releases.
     */
    private fun buildStartActivityArgs(method: Method, intent: Intent, options: Bundle): Array<Any?> {
        var seenString = false
        return method.parameterTypes.map { type ->
            when {
                type == Intent::class.java -> intent
                type == Bundle::class.java -> options
                type == String::class.java -> if (seenString) null else SHELL_PACKAGE.also { seenString = true }
                type == Int::class.javaPrimitiveType -> 0
                else -> null
            }
        }.toTypedArray()
    }

    private fun buildInjectArgs(method: Method, event: MotionEvent): Array<Any?> {
        var seenInt = false
        return method.parameterTypes.map { type ->
            when {
                type.isInstance(event) -> event
                type == Int::class.javaPrimitiveType ->
                    // The first int is the injection mode; a later one (injectInputEventToTarget's
                    // targetUid) stays 0, meaning "no specific target". Both are 0 now that the
                    // mode is ASYNC, but the positions still differ in meaning.
                    if (seenInt) 0 else INJECT_INPUT_EVENT_MODE_ASYNC.also { seenInt = true }
                else -> null
            }
        }.toTypedArray()
    }

    private fun setDisplayId(event: MotionEvent, displayId: Int): Boolean = runCatching {
        MotionEvent::class.java
            .getMethod("setDisplayId", Int::class.javaPrimitiveType)
            .invoke(event, displayId)
        true
    }.onFailure { Log.w(TAG, "MotionEvent.setDisplayId is unavailable; cannot target a display", it) }
        .getOrDefault(false)

    private fun resolveActivityApi(): ActivityApi? {
        activityApi?.let { return it }
        return runCatching {
            // "activity_task" since Android 10; "activity" is the pre-split fallback.
            val service = asShellInterface("activity_task", "android.app.IActivityTaskManager")
                ?: asShellInterface("activity", "android.app.IActivityManager")
                ?: error("Neither IActivityTaskManager nor IActivityManager could be wrapped")
            val start = service.javaClass.methods.firstOrNull { it.name == "startActivityAsUser" }
                ?: error("startActivityAsUser not found on ${service.javaClass.name}")
            ActivityApi(service, start).also { activityApi = it }
        }.onFailure { Log.w(TAG, "Could not reach the activity manager over Shizuku", it) }.getOrNull()
    }

    private fun resolveInputApi(): InputApi? {
        inputApi?.let { return it }
        return runCatching {
            val service = asShellInterface("input", "android.hardware.input.IInputManager")
                ?: error("IInputManager could not be wrapped")
            val inject = service.javaClass.methods.firstOrNull { it.name == "injectInputEvent" }
                ?: service.javaClass.methods.firstOrNull { it.name == "injectInputEventToTarget" }
                ?: error("No injectInputEvent method on ${service.javaClass.name}")
            InputApi(service, inject).also { inputApi = it }
        }.onFailure { Log.w(TAG, "Could not reach the input manager over Shizuku", it) }.getOrNull()
    }

    /** Wraps a system service binder so its transactions run as the shell UID, then asInterface()s it. */
    private fun asShellInterface(serviceName: String, interfaceName: String): Any? = runCatching {
        if (!hiddenApiExempted) error("hidden-API restriction is still in place")
        val binder = SystemServiceHelper.getSystemService(serviceName)
            ?: error("ServiceManager has no \"$serviceName\" binder")
        val wrapped = ShizukuBinderWrapper(binder)
        Class.forName("$interfaceName\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, wrapped)
    }.onFailure { Log.w(TAG, "Could not wrap $serviceName as $interfaceName: $it") }.getOrNull()
}
