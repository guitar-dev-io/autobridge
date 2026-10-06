package dev.autobridge.iptv

/** One channel in a playback queue: what the media card shows, and what the player opens. */
data class QueuedChannel(val title: String, val url: String)

/** A queue and the position in it to start from. */
data class ChannelQueue(val channels: List<QueuedChannel>, val startIndex: Int)

/**
 * The pure rules for what goes on the car's channel lists and into the playback queue, so the car
 * can be driven with the steering wheel instead of the screen.
 *
 * - **Queue**: opening a channel loads its neighbours too, so Next / Previous on the wheel or the
 *   media card (which Android Auto leaves usable while driving) step through the category.
 * - **Quick rows**: favourites, then recent channels, at the top of TV / Radio — few enough to
 *   stay inside the host's list limit while driving, so a regular channel is one tap away.
 * - **Dead channels**: a channel the check found unreachable is left out of both, and out of the
 *   lists, rather than being a row that can only fail.
 */
object IptvChannelQueue {
    /** Channels kept on each side of the one opened. A category can hold thousands. */
    const val WINDOW_EACH_SIDE = 50

    /** Quick rows at the top of TV / Radio. */
    const val QUICK_ROWS = 4

    /**
     * The queue for opening [current] from [entries]: the playable channels around it, dead ones
     * left out (but never [current] itself — the driver chose it). Null when there is nothing to
     * step to, and a single item plays on its own.
     */
    fun around(entries: List<IptvEntry>, current: IptvEntry, isDead: (String) -> Boolean): ChannelQueue? {
        val playable = entries.filter { entry ->
            entry === current || (isPlayableStream(entry) && !isDead(entry.url))
        }
        val index = playable.indexOfFirst { it === current }
        if (index < 0 || !isPlayableStream(current)) return null
        return window(playable.map { QueuedChannel(it.title, it.url) }, index)
    }

    /** The same rule for a list of remembered items (recent, favourites, quick rows). */
    fun aroundItems(
        items: List<IptvHistoryStore.Item>,
        current: IptvHistoryStore.Item,
        isDead: (String) -> Boolean,
    ): ChannelQueue? {
        val playable = items.filter { item ->
            item === current || (item.playback == IptvPlayback.STREAM && item.url.isNotBlank() && !isDead(item.url))
        }
        val index = playable.indexOfFirst { it === current }
        if (index < 0 || current.playback != IptvPlayback.STREAM || current.url.isBlank()) return null
        return window(playable.map { QueuedChannel(it.title, it.url) }, index)
    }

    /**
     * The quick rows: favourites first, then recent, one row per channel, dead channels left out,
     * at most [limit].
     */
    fun quick(
        favorites: List<IptvHistoryStore.Item>,
        recent: List<IptvHistoryStore.Item>,
        isDead: (String) -> Boolean,
        limit: Int = QUICK_ROWS,
    ): List<IptvHistoryStore.Item> =
        (favorites + recent)
            .filter { it.url.isNotBlank() && !isDead(it.url) }
            .distinctBy { it.url }
            .take(limit)

    private fun isPlayableStream(entry: IptvEntry): Boolean =
        !entry.isSeriesFolder && !entry.isWebPage && entry.url.isNotBlank()

    private fun window(channels: List<QueuedChannel>, index: Int): ChannelQueue? {
        if (channels.size < 2) return null
        val from = (index - WINDOW_EACH_SIDE).coerceAtLeast(0)
        val to = (index + WINDOW_EACH_SIDE + 1).coerceAtMost(channels.size)
        return ChannelQueue(channels.subList(from, to), index - from)
    }
}
