package dev.autobridge.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewTest {
    @Test fun aFreshInstallShowsNothing() {
        assertTrue(WhatsNew.since(null, "0.4.28").isEmpty())
    }

    @Test fun theSameVersionShowsNothing() {
        assertTrue(WhatsNew.since("0.4.28", "0.4.28").isEmpty())
    }

    @Test fun anUpdateShowsEveryReleaseSkippedNewestFirst() {
        assertEquals(
            listOf("0.4.28", "0.4.27", "0.4.26"),
            WhatsNew.since("0.4.25", "0.4.28").map { it.versionName }
        )
    }

    @Test fun releasesNewerThanTheRunningBuildAreNotShown() {
        assertEquals(listOf("0.4.26"), WhatsNew.since("0.4.25", "0.4.26").map { it.versionName })
    }

    @Test fun aLongGapIsCapped() {
        assertEquals(3, WhatsNew.since("0.4.0", "0.4.28").size)
    }
}
