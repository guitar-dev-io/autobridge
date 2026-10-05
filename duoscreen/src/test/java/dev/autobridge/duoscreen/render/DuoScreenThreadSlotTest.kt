package dev.autobridge.duoscreen.render

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The same lifecycle the compositor's GL thread goes through when the car host re-delivers its
 * Surface, with plain JVM threads standing in for HandlerThread (which needs a device).
 */
class DuoScreenThreadSlotTest {
    private val created = mutableListOf<Thread>()
    private val releases = mutableListOf<CountDownLatch>()

    /** A thread that idles until shut down, like a HandlerThread's looper. */
    private val slot = DuoScreenThreadSlot(
        create = {
            val release = CountDownLatch(1)
            releases += release
            Thread { release.await() }.apply {
                start()
                created += this
            }
        },
        shutdown = { thread ->
            releases[created.indexOf(thread)].countDown()
            thread.join(TimeUnit.SECONDS.toMillis(1))
        }
    )

    @After fun tearDown() {
        slot.stop()
        releases.forEach(CountDownLatch::countDown)
    }

    @Test fun startStopStartUsesAFreshThreadAndDoesNotThrow() {
        val first = slot.start()
        slot.stop()
        val second = slot.start()

        assertNotSame(first, second)
        assertFalse(first.isAlive)
        assertTrue(second.isAlive)
    }

    @Test fun startTwiceWithoutStopShutsDownTheFirstThread() {
        val first = slot.start()
        val second = slot.start()

        assertNotSame(first, second)
        assertFalse(first.isAlive)
        assertTrue(second.isAlive)
        assertEquals(2, created.size)
    }

    @Test fun stopLeavesNoThreadRunning() {
        repeat(3) { slot.start() }
        slot.stop()

        assertNull(slot.current)
        assertTrue(created.none(Thread::isAlive))
    }

    @Test fun stopIsSafeToCallMoreThanOnce() {
        slot.start()
        slot.stop()
        slot.stop()

        assertNull(slot.current)
        assertEquals(1, created.size)
    }
}
