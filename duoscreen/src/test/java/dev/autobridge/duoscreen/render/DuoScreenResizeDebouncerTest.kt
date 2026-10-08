package dev.autobridge.duoscreen.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuoScreenResizeDebouncerTest {
    private val debouncer = DuoScreenResizeDebouncer(quietPeriodMs = 300L)

    @Test fun doesNotFireBeforeTheQuietPeriodElapses() {
        debouncer.onResizeActivity(paneId = 0, nowMs = 1_000L)
        assertTrue(debouncer.pollReadyToCommit(nowMs = 1_299L).isEmpty())
    }

    @Test fun firesExactlyAtTheQuietPeriodBoundary() {
        debouncer.onResizeActivity(paneId = 0, nowMs = 1_000L)
        assertEquals(listOf(0), debouncer.pollReadyToCommit(nowMs = 1_300L))
    }

    @Test fun firesOnlyOncePerQuietPeriod() {
        debouncer.onResizeActivity(paneId = 0, nowMs = 1_000L)
        assertEquals(listOf(0), debouncer.pollReadyToCommit(nowMs = 1_400L))
        assertTrue(debouncer.pollReadyToCommit(nowMs = 2_000L).isEmpty())
    }

    @Test fun freshActivityResetsTheQuietPeriod() {
        debouncer.onResizeActivity(paneId = 0, nowMs = 1_000L)
        debouncer.onResizeActivity(paneId = 0, nowMs = 1_200L) // still dragging
        assertTrue(debouncer.pollReadyToCommit(nowMs = 1_300L).isEmpty()) // only 100ms since the latest activity
        assertEquals(listOf(0), debouncer.pollReadyToCommit(nowMs = 1_500L))
    }

    @Test fun tracksMultiplePanesIndependently() {
        debouncer.onResizeActivity(paneId = 0, nowMs = 1_000L)
        debouncer.onResizeActivity(paneId = 1, nowMs = 1_200L)
        assertEquals(listOf(0), debouncer.pollReadyToCommit(nowMs = 1_300L))
        assertEquals(listOf(1), debouncer.pollReadyToCommit(nowMs = 1_500L))
    }

    @Test fun cancelDropsAPendingCommitForOnePane() {
        debouncer.onResizeActivity(paneId = 0, nowMs = 1_000L)
        debouncer.onResizeActivity(paneId = 1, nowMs = 1_000L)
        debouncer.cancel(0)
        assertEquals(listOf(1), debouncer.pollReadyToCommit(nowMs = 1_300L))
    }

    @Test fun cancelAllDropsEveryPendingCommit() {
        debouncer.onResizeActivity(paneId = 0, nowMs = 1_000L)
        debouncer.onResizeActivity(paneId = 1, nowMs = 1_000L)
        debouncer.cancelAll()
        assertTrue(debouncer.pollReadyToCommit(nowMs = 1_300L).isEmpty())
    }

    @Test fun aSeamDragWaitsForItsLongerQuietPeriod() {
        debouncer.onResizeActivity(paneId = 0, nowMs = 1_000L, quietMs = 700L)
        assertTrue(debouncer.pollReadyToCommit(nowMs = 1_400L).isEmpty()) // a pause, not the end
        assertEquals(listOf(0), debouncer.pollReadyToCommit(nowMs = 1_700L))
    }
}
