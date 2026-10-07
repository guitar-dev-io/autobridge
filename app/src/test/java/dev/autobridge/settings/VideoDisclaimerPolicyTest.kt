package dev.autobridge.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDisclaimerPolicyTest {
    private val day = 24L * 60 * 60 * 1000
    private val at = 1_000 * day
    private val v = VideoDisclaimerPolicy.VERSION

    @Test fun anAcceptanceStandsForThreeMonths() {
        assertTrue(VideoDisclaimerPolicy.stands(at, v, at))
        assertTrue(VideoDisclaimerPolicy.stands(at, v, at + 89 * day))
    }

    @Test fun itLapsesAfterThreeMonths() {
        assertFalse(VideoDisclaimerPolicy.stands(at, v, at + 90 * day))
        assertFalse(VideoDisclaimerPolicy.stands(at, v, at + 400 * day))
    }

    @Test fun anAcceptanceFromBeforeDatesExistedIsAskedAgain() {
        assertFalse(VideoDisclaimerPolicy.stands(0L, 0, at))
        assertFalse(VideoDisclaimerPolicy.stands(0L, v, at))
    }

    @Test fun newWordingIsAskedAgainEvenWhenRecent() {
        assertFalse(VideoDisclaimerPolicy.stands(at, v - 1, at + day))
    }

    @Test fun aClockSetBackDoesNotKeepItAliveForever() {
        assertFalse(VideoDisclaimerPolicy.stands(at, v, at - day))
    }
}
