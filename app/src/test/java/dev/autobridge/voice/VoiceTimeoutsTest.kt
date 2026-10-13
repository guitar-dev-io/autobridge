package dev.autobridge.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTimeoutsTest {
    @Test fun aShortCommandGetsTheFloor() {
        assertEquals(15_000L, VoiceTimeouts.transcribeLimitMs(2_000))
    }

    @Test fun aLongerOneGetsThreeTimesItsLength() {
        assertEquals(24_000L, VoiceTimeouts.transcribeLimitMs(8_000))
    }

    @Test fun nothingWaitsPastTheCeiling() {
        assertEquals(30_000L, VoiceTimeouts.transcribeLimitMs(60_000))
        assertEquals(15_000L, VoiceTimeouts.transcribeLimitMs(-1))
    }
}
