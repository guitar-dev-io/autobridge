package dev.autobridge.rear.wifi

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phone and tablet on one device: rear screen mode open, then the phone side finds it on the
 * network, pairs with the code it shows, sends a video and drives it, all over real sockets.
 *
 * The video comes from the `rearUrl` instrumentation argument (on an emulator, a file served from
 * the Mac at `http://10.0.2.2:8765/test.mp4`); without it the playing steps are skipped.
 */
@RunWith(AndroidJUnit4::class)
class RearWifiEndToEndTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before fun openRearScreenMode() {
        context.getSharedPreferences("rear_tablets", Context.MODE_PRIVATE).edit().clear().commit()
        context.startActivity(RearReceiverActivity.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue("rear screen mode did not open", waitFor(10_000) { RearReceiverActivity.shown?.portForTest ?: 0 > 0 })
    }

    @After fun close() {
        RearSender.cancel(context)
        instrumentation.runOnMainSync { RearReceiverActivity.shown?.finish() }
        waitFor(5_000) { RearReceiverActivity.shown == null }
    }

    @Test fun pairSendMuteAndStop() {
        val receiver = RearReceiverActivity.shown!!
        val port = receiver.portForTest

        // A wrong code is refused; the code on screen pairs.
        assertEquals(RearClient.PairResult.WrongCode, RearClient.pair("127.0.0.1", port, wrongCode(receiver.codeForTest)))
        val info = RearClient.info("127.0.0.1", port)
        assertNotNull("tablet did not answer /info", info)
        info!!
        val paired = RearClient.pair("127.0.0.1", port, receiver.codeForTest) as RearClient.PairResult.Paired
        RearTablets.save(context, RearTablets.Tablet(info.id, info.name, paired.token, "127.0.0.1", port))

        // Unpaired commands are refused.
        assertEquals(401, RearClient.call("127.0.0.1", port, "POST", "/stop", "{}", "not-a-token")?.code)

        val url = InstrumentationRegistry.getArguments().getString("rearUrl")
        assumeTrue("needs -e rearUrl <video>", !url.isNullOrBlank())

        var sent: Boolean? = null
        instrumentation.runOnMainSync {
            RearSender.send(context, RearProtocol.Play(url!!, "Rear test", 0L, muted = true)) { sent = it }
        }
        assertTrue("send did not finish", waitFor(10_000) { sent != null })
        assertEquals(true, sent)
        assertTrue("tablet did not play", waitFor(20_000) { receiver.statusForTest.playing })
        assertTrue("tablet should start muted", receiver.statusForTest.muted)

        RearSender.control(context, RearSender.ACTION_MUTE)
        assertTrue("sound did not turn on", waitFor(5_000) { !receiver.statusForTest.muted })
        RearSender.control(context, RearSender.ACTION_TOGGLE)
        assertTrue("pause did not reach the tablet", waitFor(5_000) { !receiver.statusForTest.playing })
        RearSender.control(context, RearSender.ACTION_STOP)
        assertTrue("stop did not reach the tablet", waitFor(5_000) { receiver.statusForTest.title.isEmpty() })
    }

    @Test fun thePhoneFindsTheTabletOnTheNetwork() {
        val id = RearClient.info("127.0.0.1", RearReceiverActivity.shown!!.portForTest)!!.id
        var found: RearFinder.Found? = null
        lateinit var finder: RearFinder
        instrumentation.runOnMainSync {
            finder = RearFinder(context) { if (it.info.id == id) found = it }
            finder.start()
        }
        val ok = waitFor(15_000) { found != null }
        instrumentation.runOnMainSync { finder.stop() }
        assertTrue("the tablet was not found by NSD", ok)
    }

    private fun wrongCode(code: String) = if (code == "0000") "0001" else "0000"

    private fun waitFor(timeoutMs: Long, check: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            var ok = false
            instrumentation.runOnMainSync { ok = check() }
            if (ok) return true
            SystemClock.sleep(200)
        }
        return false
    }
}
