package dev.autobridge.radio

import org.junit.Assert.assertEquals
import org.junit.Test

class RadioSleepTimerTest {
    @Test fun noTimerHasNothingLeft() = assertEquals(0, RadioSleepTimer.remainingMinutes(0L, 5_000L))

    @Test fun aPassedTimerHasNothingLeft() = assertEquals(0, RadioSleepTimer.remainingMinutes(10_000L, 20_000L))

    @Test fun theLastMinuteReadsOne() = assertEquals(1, RadioSleepTimer.remainingMinutes(60_000L, 1_000L))

    @Test fun minutesRoundUp() {
        assertEquals(30, RadioSleepTimer.remainingMinutes(30 * 60_000L, 0L))
        assertEquals(30, RadioSleepTimer.remainingMinutes(30 * 60_000L, 1L))
        assertEquals(29, RadioSleepTimer.remainingMinutes(30 * 60_000L, 60_000L))
    }
}
