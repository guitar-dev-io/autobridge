package dev.autobridge.duoscreen.system

import android.content.ComponentName
import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The degradation contract: an implementation that does not provide the trusted-display capability
 * (anything but [DuoScreenShizukuOps], or the shell path failing) must report it as unavailable so
 * [dev.autobridge.duoscreen.render.DuoScreenDisplays] falls back to the ordinary untrusted display
 * and the session behaves exactly as before the fix. The interface defaults encode that, so this
 * guards them at the JVM level without a device.
 */
class DuoScreenPrivilegedOpsDefaultsTest {
    /** Overrides only the two always-required ops, leaving the trusted-display ones at default. */
    private val onlyLaunchAndInject = object : DuoScreenPrivilegedOps {
        override val isAvailable: Boolean = true
        override fun launchOnDisplay(
            displayId: Int,
            packageName: String,
            component: ComponentName?,
            allowSecondInstance: Boolean,
        ): Boolean = true
        override fun injectMotion(event: MotionEvent, displayId: Int): Boolean = true
    }

    @Test fun createTrustedDisplayDefaultsToUnavailable() {
        // The default never touches the surface, so the android.jar stub instance is enough.
        val surface = Surface(SurfaceTexture(0))
        assertEquals(-1, onlyLaunchAndInject.createTrustedVirtualDisplay("p", 100, 200, 320, surface, 0))
    }

    @Test fun resizeTrustedDisplayDefaultsToFalse() {
        assertFalse(onlyLaunchAndInject.resizeTrustedVirtualDisplay(7, 100, 200, 320))
    }

    @Test fun setTrustedSurfaceDefaultsToFalse() {
        assertFalse(onlyLaunchAndInject.setTrustedVirtualDisplaySurface(7, null))
    }

    @Test fun releaseTrustedDisplayDefaultsToNoOp() {
        // Default is a no-op; it must not throw for an id it never created.
        onlyLaunchAndInject.releaseTrustedVirtualDisplay(7)
    }
}
