package dev.autobridge.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcAlertPolicyTest {
    private fun report(stored: List<String>, pending: List<String> = emptyList(), answered: Boolean = true) =
        ObdConnection.DtcReport(stored, pending, storedAnswered = answered)

    @Test fun aStoredCodeIsAnnouncedTheFirstTimeItIsSeen() {
        val outcome = DtcAlertPolicy.evaluate(emptySet(), report(listOf("P0133")))
        assertEquals(listOf("P0133"), outcome.announce)
        assertEquals(setOf("P0133"), outcome.remember)
    }

    @Test fun aCodeThatStaysStoredIsNotAnnouncedAgain() {
        val outcome = DtcAlertPolicy.evaluate(setOf("P0133"), report(listOf("P0133")))
        assertTrue(outcome.announce.isEmpty())
        assertEquals(setOf("P0133"), outcome.remember)
    }

    @Test fun onlyTheNewCodeIsAnnouncedWhenAnotherJoinsIt() {
        val outcome = DtcAlertPolicy.evaluate(setOf("P0133"), report(listOf("P0133", "P0300")))
        assertEquals(listOf("P0300"), outcome.announce)
    }

    @Test fun pendingCodesAreNeverAnnounced() {
        val outcome = DtcAlertPolicy.evaluate(emptySet(), report(emptyList(), pending = listOf("P0420")))
        assertTrue(outcome.announce.isEmpty())
        assertEquals(emptySet<String>(), outcome.remember)
    }

    @Test fun aClearedCodeThatComesBackIsAnnouncedAgain() {
        val cleared = DtcAlertPolicy.evaluate(setOf("P0133"), report(emptyList()))
        assertEquals(emptySet<String>(), cleared.remember)
        val back = DtcAlertPolicy.evaluate(cleared.remember!!, report(listOf("P0133")))
        assertEquals(listOf("P0133"), back.announce)
    }

    @Test fun aReadTheCarDidNotAnswerChangesNothing() {
        val outcome = DtcAlertPolicy.evaluate(setOf("P0133"), report(emptyList(), answered = false))
        assertTrue(outcome.announce.isEmpty())
        assertNull(outcome.remember)
    }

    @Test fun answeredTellsNoCodesFromNoAnswer() {
        assertTrue(DtcParser.answered("43 00 00 00 00 00 00\r", 0x43))
        assertTrue(DtcParser.answered("43 01 01 33 00 00 00\r", 0x43))
        assertTrue(DtcParser.answered("NO DATA\r", 0x43))
        assertTrue(DtcParser.answered("00A\r0: 43 04 01 33 03\r1: 00 02 34 04 56\r", 0x43))
        assertFalse(DtcParser.answered("", 0x43))
        assertFalse(DtcParser.answered("CAN ERROR\r", 0x43))
        assertFalse(DtcParser.answered("STOPPED\r", 0x43))
        assertFalse(DtcParser.answered("47 01 01 33 00 00 00\r", 0x43))
    }
}
