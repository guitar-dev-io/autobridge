package dev.autobridge.duoscreen.system

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.view.MotionEvent
import android.view.Surface
import dev.autobridge.duoscreen.DuoLog
import dev.autobridge.shizuku.ShizukuGrant
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * The shell-wrapped IDisplayManager plus the methods a trusted display needs over its lifetime.
     * Signatures are reflected once and reused; see IDisplayManager AIDL —
     * `int createVirtualDisplay(VirtualDisplayConfig, IVirtualDisplayCallback, IMediaProjection, String)`,
     * `void releaseVirtualDisplay(IVirtualDisplayCallback)`,
     * `void resizeVirtualDisplay(IVirtualDisplayCallback, int, int, int)`,
     * `void setVirtualDisplaySurface(IVirtualDisplayCallback, Surface)`.
     */
    private data class DisplayApi(
        val service: Any,
        val create: Method,
        val release: Method?,
        val resize: Method?,
        val setSurface: Method?,
    )

    @Volatile
    private var activityApi: ActivityApi? = null

    @Volatile
    private var inputApi: InputApi? = null

    @Volatile
    private var displayApi: DisplayApi? = null

    /**
     * The callback token each trusted display was created with, keyed by its display id. The token
     * is the identity the system matches on for release/resize, so it must be the very object
     * passed to `createVirtualDisplay`, kept until the display is released.
     */
    private val displayTokens = ConcurrentHashMap<Int, Any>()

    /**
     * Both Stub.asInterface methods are hidden-API blocked at this target SDK ("api=blocked" for
     * IActivityTaskManager, "max-target-r" for IActivityManager), so plain reflection is denied.
     * The empty prefix exempts everything, which is what reaching an arbitrary system interface
     * needs; done once, lazily, so a flavor without the library simply reports unavailable.
     */
    private val hiddenApiExempted: Boolean by lazy {
        runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
            .onFailure { DuoLog.w(TAG, "Could not lift the hidden-API restriction", it) }
            .getOrDefault(false)
    }

    /** The one Shizuku grant the app holds; Duo Screen never asks for a second one. */
    override val isAvailable: Boolean
        get() = ShizukuGrant.isGranted

    fun reset() {
        activityApi = null
        inputApi = null
        displayApi = null
        // The tokens are only valid against the server that minted them; a reconnected Shizuku
        // server does not know them, so drop them with the stale api handle.
        displayTokens.clear()
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
            if (!started) DuoLog.w(TAG, "startActivityAsUser($packageName -> display $displayId) returned $result")
            started
        }.onFailure { error ->
            activityApi = null
            DuoLog.w(TAG, "startActivityAsUser failed for $packageName on display $displayId", error)
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
            DuoLog.w(TAG, "injectInputEvent failed on display $displayId", error)
        }.getOrDefault(false)
    }

    override fun createTrustedVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        flags: Int,
    ): Int {
        if (!isAvailable) return -1
        val api = resolveDisplayApi() ?: return -1
        return runCatching {
            val config = buildVirtualDisplayConfig(name, width, height, dpi, surface, flags)
                ?: error("VirtualDisplayConfig.Builder is unavailable")
            val token = newVirtualDisplayCallback()
                ?: error("IVirtualDisplayCallback could not be built")
            val args = buildCreateDisplayArgs(api.create, config, token)
            val displayId = api.create.invoke(api.service, *args) as? Int ?: -1
            if (displayId < 0) {
                DuoLog.w(TAG, "createVirtualDisplay(trusted $name) returned $displayId")
            } else {
                // Keep the token so release/resize can match it later.
                displayTokens[displayId] = token
                DuoLog.i(TAG, "Trusted display created: $name -> $displayId (${width}x$height @ ${dpi}dpi)")
            }
            displayId
        }.onFailure { error ->
            displayApi = null
            DuoLog.w(TAG, "createTrustedVirtualDisplay failed for $name", error)
        }.getOrDefault(-1)
    }

    override fun resizeTrustedVirtualDisplay(displayId: Int, width: Int, height: Int, dpi: Int): Boolean {
        val token = displayTokens[displayId] ?: return false
        val api = resolveDisplayApi() ?: return false
        val resize = api.resize ?: run {
            DuoLog.w(TAG, "No resizeVirtualDisplay method; display $displayId not resized over shell")
            return false
        }
        return runCatching {
            resize.invoke(api.service, token, width, height, dpi)
            true
        }.onFailure { DuoLog.w(TAG, "resizeVirtualDisplay($displayId) failed", it) }.getOrDefault(false)
    }

    override fun setTrustedVirtualDisplaySurface(displayId: Int, surface: Surface?): Boolean {
        val token = displayTokens[displayId] ?: return false
        val api = resolveDisplayApi() ?: return false
        val setSurface = api.setSurface ?: run {
            DuoLog.w(TAG, "No setVirtualDisplaySurface method; display $displayId surface not set over shell")
            return false
        }
        return runCatching {
            setSurface.invoke(api.service, token, surface)
            true
        }.onFailure { DuoLog.w(TAG, "setVirtualDisplaySurface($displayId) failed", it) }.getOrDefault(false)
    }

    override fun releaseTrustedVirtualDisplay(displayId: Int) {
        val token = displayTokens.remove(displayId) ?: return
        val api = resolveDisplayApi() ?: return
        val release = api.release ?: run {
            DuoLog.w(TAG, "No releaseVirtualDisplay method; display $displayId not released over shell")
            return
        }
        runCatching { release.invoke(api.service, token) }
            .onFailure { DuoLog.w(TAG, "releaseVirtualDisplay($displayId) failed", it) }
    }

    /**
     * Builds a [android.hardware.display.VirtualDisplayConfig] carrying [flags] through its hidden
     * Builder, reflectively because the Builder's trusted-flag setters are @hide. Name/width/
     * height/dpi go through the public Builder constructor; the surface and flags through the
     * hidden setters that `DisplayManager.createVirtualDisplay(...)` uses internally.
     */
    private fun buildVirtualDisplayConfig(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        flags: Int,
    ): Any? = runCatching {
        val builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
        val builder = builderClass
            .getConstructor(String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(name, width, height, dpi)
        builderClass.getMethod("setFlags", Int::class.javaPrimitiveType).invoke(builder, flags)
        builderClass.getMethod("setSurface", Surface::class.java).invoke(builder, surface)
        builderClass.getMethod("build").invoke(builder)
    }.onFailure { DuoLog.w(TAG, "Could not build VirtualDisplayConfig", it) }.getOrNull()

    /**
     * A minimal [android.hardware.display.IVirtualDisplayCallback] whose only job is to be a stable
     * binder identity: the system matches resize/release against the token's `asBinder()`, and
     * nothing calls back into it for the uses here (the display paints from its Surface). A
     * [Proxy] over the interface, returning a real local [Binder] from `asBinder()`, is that
     * identity without needing the hidden abstract `Stub`.
     */
    private fun newVirtualDisplayCallback(): Any? = runCatching {
        val callbackClass = Class.forName("android.hardware.display.IVirtualDisplayCallback")
        val binder = Binder()
        Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { _, method, args ->
            when (method.name) {
                "asBinder" -> binder
                "toString" -> "AutoBridgeDuoVirtualDisplayCallback"
                "hashCode" -> binder.hashCode()
                "equals" -> args?.getOrNull(0) === binder
                else -> null
            }
        }
    }.onFailure { DuoLog.w(TAG, "Could not build IVirtualDisplayCallback", it) }.getOrNull()

    /**
     * Fills createVirtualDisplay's parameters positionally by type: the config, the callback token,
     * null for the IMediaProjection (we hold none; the trusted flag is what grants the display,
     * not a projection token), and the shell package for the String. Done by type, like
     * [buildStartActivityArgs], so a parameter-list change across releases does not break it.
     */
    private fun buildCreateDisplayArgs(method: Method, config: Any, token: Any): Array<Any?> {
        val configClass = config.javaClass
        val callbackClass = token.javaClass
        return method.parameterTypes.map { type ->
            when {
                type.isAssignableFrom(configClass) -> config
                type.isAssignableFrom(callbackClass) -> token
                type == String::class.java -> SHELL_PACKAGE
                else -> null // IMediaProjection projectionToken, and any future added slot
            }
        }.toTypedArray()
    }

    private fun resolveDisplayApi(): DisplayApi? {
        displayApi?.let { return it }
        return runCatching {
            val service = asShellInterface("display", "android.hardware.display.IDisplayManager")
                ?: error("IDisplayManager could not be wrapped")
            val methods = service.javaClass.methods
            val create = methods.firstOrNull { it.name == "createVirtualDisplay" }
                ?: error("createVirtualDisplay not found on ${service.javaClass.name}")
            val release = methods.firstOrNull { it.name == "releaseVirtualDisplay" }
            val resize = methods.firstOrNull { it.name == "resizeVirtualDisplay" }
            val setSurface = methods.firstOrNull { it.name == "setVirtualDisplaySurface" }
            DisplayApi(service, create, release, resize, setSurface).also { displayApi = it }
        }.onFailure { DuoLog.w(TAG, "Could not reach the display manager over Shizuku", it) }.getOrNull()
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
    }.onFailure { DuoLog.w(TAG, "MotionEvent.setDisplayId is unavailable; cannot target a display", it) }
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
        }.onFailure { DuoLog.w(TAG, "Could not reach the activity manager over Shizuku", it) }.getOrNull()
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
        }.onFailure { DuoLog.w(TAG, "Could not reach the input manager over Shizuku", it) }.getOrNull()
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
    }.onFailure { DuoLog.w(TAG, "Could not wrap $serviceName as $interfaceName: $it") }.getOrNull()
}
