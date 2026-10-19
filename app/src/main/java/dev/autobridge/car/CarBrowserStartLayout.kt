package dev.autobridge.car

import dev.autobridge.entertainment.WebBookmark
import dev.autobridge.entertainment.WebHistoryEntry

/**
 * Pure budget + de-dup arithmetic for the car browser start grid, so it can be unit tested without
 * a `CarContext`. The grid is a flat, ordered [GridTemplate] single list: the search "Go" cell, the
 * fixed tiles (continue/open-browser + support), then the most recent pages, then bookmarks fill
 * whatever space is left. The total never exceeds the host's content limit, which would otherwise
 * throw at runtime.
 *
 * Recent mirrors the phone start page: newest first, capped at [RECENT_COUNT]. A recent page whose
 * url equals the live "continue" page ([liveUrl]) is dropped so the same page is not offered twice
 * (string compare, since `BrowserSiteEntry.resumes` only matches bare site roots and history urls
 * usually carry a path).
 */
internal object CarBrowserStartLayout {
    /** How many most-recent pages the car start grid shows, mirroring the phone side's cap. */
    const val RECENT_COUNT = 3

    data class Result(val recent: List<WebHistoryEntry>, val bookmarks: List<WebBookmark>)

    /**
     * @param limit     the host's grid content limit (total cells allowed).
     * @param fixedCount how many fixed tiles follow the search cell (continue/open-browser + support).
     * @param liveUrl    the live "continue" page url, or null; a recent page matching it is dropped.
     * @param history    visited pages, newest first (as [WebHistoryStore.list] returns them).
     * @param bookmarks  user bookmarks, in display order.
     */
    fun compute(
        limit: Int,
        fixedCount: Int,
        liveUrl: String?,
        history: List<WebHistoryEntry>,
        bookmarks: List<WebBookmark>,
    ): Result {
        // Space left after the search "Go" cell and the fixed tiles.
        val remaining = (limit - 1 - fixedCount).coerceAtLeast(0)
        val recent = history
            .asSequence()
            .filter { it.url != liveUrl }
            .take(RECENT_COUNT.coerceAtMost(remaining))
            .toList()
        val bookmarkRoom = (remaining - recent.size).coerceAtLeast(0)
        return Result(recent, bookmarks.take(bookmarkRoom))
    }
}
