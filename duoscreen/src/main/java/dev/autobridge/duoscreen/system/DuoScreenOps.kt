package dev.autobridge.duoscreen.system

import android.content.ComponentName
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import dev.autobridge.duoscreen.layout.DuoScreenStore
import dev.autobridge.shizuku.ShizukuGrant

/**
 * The privileged ops Duo Screen actually uses: whichever of the two backends is ready, the one the
 * driver chose ([DuoScreenStore.backend]) first.
 *
 * - **Shizuku** ([DuoScreenShizukuOps]): no root needed, but Shizuku has to be started again after
 *   every reboot. Gives trusted pane displays that survive the phone's power button.
 * - **Root** ([DuoScreenRootOps]): no Shizuku needed, on a rooted phone. Launch, touch and keys
 *   work the same; the panes are ordinary app displays.
 *
 * The trusted-display calls always go to Shizuku: it is the only backend that can make one, and a
 * display has to be resized and released by the backend that created it. Without Shizuku they
 * report "unavailable" and [dev.autobridge.duoscreen.render.DuoScreenDisplays] falls back to an
 * untrusted display, exactly as before.
 */
object DuoScreenOps : DuoScreenPrivilegedOps {
    @Volatile
    var preferred: DuoScreenBackend = DuoScreenBackend.SHIZUKU
        private set

    /** Reads the driver's choice; done when a session is created and when the choice changes. */
    fun load(context: Context) {
        preferred = DuoScreenStore.backend(context)
    }

    /** Stores [backend] as the driver's choice and starts or stops the root helper to match. */
    fun choose(context: Context, backend: DuoScreenBackend, onReady: (Boolean) -> Unit = {}) {
        DuoScreenStore.setBackend(context, backend)
        preferred = backend
        when (backend) {
            DuoScreenBackend.ROOT -> DuoScreenRootOps.startAsync(context, onReady)
            DuoScreenBackend.SHIZUKU -> {
                DuoScreenRootOps.stop()
                onReady(DuoScreenShizukuOps.isAvailable)
            }
        }
    }

    /**
     * Brings the chosen backend up if it can be done from here: the root helper is started (which
     * may prompt); Shizuku can only be granted from its own app. [onReady] runs on the main thread
     * once the root helper is up, only if it was not already.
     */
    fun prepare(context: Context, onReady: () -> Unit) {
        load(context)
        if (preferred == DuoScreenBackend.ROOT && !DuoScreenRootOps.isAvailable) {
            DuoScreenRootOps.startAsync(context) { started -> if (started) onReady() }
        }
    }

    /** What the driver has to do before the panes can run their apps; see [setupStep]. */
    enum class SetupStep { INSTALL_SHIZUKU, START_SHIZUKU, ALLOW_SHIZUKU, ALLOW_ROOT }

    /**
     * The one thing standing between the driver and working panes, or null when a backend is
     * ready. For Shizuku it is told apart — not installed, installed but stopped (every reboot
     * stops it), running without the grant — because each is a different thing to do on the phone.
     */
    fun setupStep(context: Context): SetupStep? {
        if (isAvailable) return null
        return when (preferred) {
            DuoScreenBackend.ROOT -> SetupStep.ALLOW_ROOT
            DuoScreenBackend.SHIZUKU -> when (ShizukuGrant.state(context)) {
                ShizukuGrant.State.NOT_INSTALLED -> SetupStep.INSTALL_SHIZUKU
                ShizukuGrant.State.NOT_RUNNING -> SetupStep.START_SHIZUKU
                ShizukuGrant.State.NOT_GRANTED, ShizukuGrant.State.READY -> SetupStep.ALLOW_SHIZUKU
            }
        }
    }

    /** The backend calls go to now, or null when neither is ready. */
    val active: DuoScreenBackend?
        get() = order().firstOrNull { backendOps(it).isAvailable }

    private fun order(): List<DuoScreenBackend> = when (preferred) {
        DuoScreenBackend.SHIZUKU -> listOf(DuoScreenBackend.SHIZUKU, DuoScreenBackend.ROOT)
        DuoScreenBackend.ROOT -> listOf(DuoScreenBackend.ROOT, DuoScreenBackend.SHIZUKU)
    }

    private fun backendOps(backend: DuoScreenBackend): DuoScreenPrivilegedOps = when (backend) {
        DuoScreenBackend.SHIZUKU -> DuoScreenShizukuOps
        DuoScreenBackend.ROOT -> DuoScreenRootOps
    }

    private val current: DuoScreenPrivilegedOps?
        get() = active?.let(::backendOps)

    override val isAvailable: Boolean
        get() = active != null

    override fun createTrustedVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        flags: Int,
    ): Int = DuoScreenShizukuOps.createTrustedVirtualDisplay(name, width, height, dpi, surface, flags)

    override fun resizeTrustedVirtualDisplay(displayId: Int, width: Int, height: Int, dpi: Int): Boolean =
        DuoScreenShizukuOps.resizeTrustedVirtualDisplay(displayId, width, height, dpi)

    override fun setTrustedVirtualDisplaySurface(displayId: Int, surface: Surface?): Boolean =
        DuoScreenShizukuOps.setTrustedVirtualDisplaySurface(displayId, surface)

    override fun releaseTrustedVirtualDisplay(displayId: Int) =
        DuoScreenShizukuOps.releaseTrustedVirtualDisplay(displayId)

    override fun launchOnDisplay(
        displayId: Int,
        packageName: String,
        component: ComponentName?,
        allowSecondInstance: Boolean,
    ): Boolean = current?.launchOnDisplay(displayId, packageName, component, allowSecondInstance) ?: false

    override fun hasTaskOnDisplay(displayId: Int): Boolean? = current?.hasTaskOnDisplay(displayId)

    override fun showImeOnDisplay(displayId: Int): Boolean = current?.showImeOnDisplay(displayId) ?: false

    override fun injectMotion(event: MotionEvent, displayId: Int): Boolean =
        current?.injectMotion(event, displayId) ?: false

    override fun injectKey(event: KeyEvent, displayId: Int): Boolean =
        current?.injectKey(event, displayId) ?: false
}
