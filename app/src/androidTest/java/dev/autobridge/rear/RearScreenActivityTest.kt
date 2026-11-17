package dev.autobridge.rear

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.autobridge.display.RearDisplays
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The rear player opens on the rear screen, not on the phone, and plays there.
 *
 * Needs a second display. On an emulator or a test phone, add one with
 * `adb shell settings put global overlay_display_devices 1280x720/240` (Developer options →
 * "Simulate secondary displays"). Without one the test is skipped.
 *
 * The video comes from the `rearUrl` instrumentation argument (for example a file served from
 * the Mac at `http://10.0.2.2:8765/test.mp4` on an emulator), so the test does not depend on a
 * public sample that may disappear.
 */
@RunWith(AndroidJUnit4::class)
class RearScreenActivityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After fun close() {
        RearScreenActivity.stop(context)
        waitFor(5_000) { RearScreenActivity.shown == null }
    }

    @Test fun playsOnTheRearScreenNotThePhone() {
        val rear = RearDisplays.find(context)
        assumeTrue("needs a second display (overlay_display_devices)", rear != null)
        val url = InstrumentationRegistry.getArguments().getString("rearUrl")
        assumeTrue("needs -e rearUrl <video>", !url.isNullOrBlank())

        assertTrue(RearScreenActivity.send(context, url!!, "Rear test", 0L))

        assertTrue("rear player did not open", waitFor(10_000) { RearScreenActivity.shown != null })
        val shown = RearScreenActivity.shown!!
        assertEquals(rear!!.displayId, shown.displayIdForTest)
        assertTrue("video did not start", waitFor(20_000) { shown.isPlayingForTest })
        // `-e holdMs 15000` keeps it playing for a look or a screenshot before the test closes it.
        InstrumentationRegistry.getArguments().getString("holdMs")?.toLongOrNull()?.let { SystemClock.sleep(it) }
    }

    @Test fun sentWhileTheFrontPlaysItStartsMutedAndCanTurnItsSoundOn() {
        assumeTrue(RearDisplays.find(context) != null)
        val url = InstrumentationRegistry.getArguments().getString("rearUrl")
        assumeTrue(!url.isNullOrBlank())

        assertTrue(RearScreenActivity.send(context, url!!, "Rear test", 0L, muted = true))
        assertTrue(waitFor(10_000) { RearScreenActivity.shown != null })
        val shown = RearScreenActivity.shown!!
        assertTrue("rear should start without sound", shown.isMutedForTest)
        assertTrue("muted video should still play", waitFor(20_000) { shown.isPlayingForTest })

        RearScreenActivity.toggleMute(context)
        assertTrue("sound did not turn on", waitFor(5_000) { !shown.isMutedForTest })
    }

    @Test fun stopClosesTheRearPlayer() {
        assumeTrue(RearDisplays.find(context) != null)
        val url = InstrumentationRegistry.getArguments().getString("rearUrl")
        assumeTrue(!url.isNullOrBlank())

        RearScreenActivity.send(context, url!!, "Rear test", 0L)
        assertTrue(waitFor(10_000) { RearScreenActivity.shown != null })
        RearScreenActivity.stop(context)
        assertTrue("stop did not close it", waitFor(5_000) { RearScreenActivity.shown == null })
    }

    private fun waitFor(timeoutMs: Long, check: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            var ok = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync { ok = check() }
            if (ok) return true
            SystemClock.sleep(200)
        }
        return false
    }
}
