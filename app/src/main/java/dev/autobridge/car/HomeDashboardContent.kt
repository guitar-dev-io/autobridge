package dev.autobridge.car

import android.content.Context
import dev.autobridge.browser.BrowserPlayQueue
import dev.autobridge.bridge.BridgeSource
import dev.autobridge.bridge.BridgeStore
import dev.autobridge.core.state.RecentActivityStore
import dev.autobridge.library.HomeSection
import dev.autobridge.youtube.YouTubeUrls

/**
 * What the home dashboard has to show, read once from the stores that already own it.
 *
 * Nothing here is invented: Continue Watching is the bridge's own session snapshot
 * ([BridgeStore.lastSession]), Recently Sent is [RecentActivityStore] filtered to the entries that
 * actually arrived from the phone, and the queue is [BrowserPlayQueue]. A section with no real
 * data is absent, and [HomeDashboardLayout] then gives its space to the rest of the screen rather
 * than drawing an empty frame.
 *
 * Reading is deliberately a single snapshot taken when the screen starts, not a read per rendered
 * frame: all three stores are JSON in SharedPreferences and the dashboard re-renders on every
 * scroll pixel.
 */
internal data class HomeDashboardContent(
    val continueWatching: ContinueItem? = null,
    val recentlySent: List<SentItem> = emptyList(),
    val queue: List<QueueItem> = emptyList(),
    /** Full queue length, which may be larger than the [queue] rows the home shows. */
    val queueTotal: Int = 0
) {
    val hasContinueWatching: Boolean get() = continueWatching != null
    val hasRecentlySent: Boolean get() = recentlySent.isNotEmpty()
    val hasQueue: Boolean get() = queue.isNotEmpty()

    companion object {
        /** The home shows a taste of each list; the full ones are one tap away on their screens. */
        const val MAX_RECENTLY_SENT = 2
        const val MAX_QUEUE_ROWS = 3

        fun read(context: Context): HomeDashboardContent {
            val snapshot = BridgeStore.lastSession(context)
            val sent = RecentActivityStore.list(context)
                .filter { it.origin == RecentActivityStore.Origin.PHONE || it.origin == RecentActivityStore.Origin.SHARE }
                .filter { !it.data.isNullOrBlank() }
                .take(MAX_RECENTLY_SENT)
            val queue = BrowserPlayQueue.items(context)
            return HomeDashboardContent(
                continueWatching = snapshot?.let { ContinueItem.of(context, it) },
                recentlySent = sent.map { SentItem.of(context, it) },
                queue = queue.take(MAX_QUEUE_ROWS).map { QueueItem.of(context, it) },
                queueTotal = queue.size
            )
        }
    }
}

/** A thumbnail's source and the artwork to fall back to when there is no image for it. */
internal data class Artwork(
    val glyph: HomeMenuGlyph,
    val accent: Int,
    /** Remote image for this address, or null when the source offers none (see [CarThumbnails]). */
    val thumbnailUrl: String?
)

/** The resumable session, as the hero card needs it. */
internal data class ContinueItem(
    val source: BridgeSource,
    val snapshot: BridgeStore.Snapshot,
    val title: String,
    val sourceLabel: String,
    val artwork: Artwork,
    val positionMs: Long,
    val durationMs: Long
) {
    /** 0–1, or null when the engine never reported a duration and a bar would be a guess. */
    val progress: Float?
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else null

    companion object {
        fun of(context: Context, snapshot: BridgeStore.Snapshot) = ContinueItem(
            source = snapshot.source,
            snapshot = snapshot,
            title = snapshot.source.displayTitle,
            sourceLabel = HomeDashboardSource.label(context, snapshot.source.url),
            artwork = HomeDashboardSource.artwork(snapshot.source.url),
            positionMs = snapshot.positionMs,
            durationMs = snapshot.durationMs
        )
    }
}

/** One Recently Sent row: what arrived from the phone, and how long ago. */
internal data class SentItem(
    val url: String,
    val title: String,
    /** "YouTube · from phone · 28 min ago", already localised and joined. */
    val meta: String,
    val artwork: Artwork
) {
    companion object {
        fun of(context: Context, entry: RecentActivityStore.Entry): SentItem {
            val url = entry.data.orEmpty()
            val parts = listOf(
                HomeDashboardSource.label(context, url),
                context.getString(dev.autobridge.R.string.car_home_sent_from_phone),
                RecentActivityStore.relativeAge(context, entry.timestampMs)
            ).filter { it.isNotBlank() }
            return SentItem(
                url = url,
                title = entry.title,
                meta = parts.joinToString(SEPARATOR),
                artwork = HomeDashboardSource.artwork(url)
            )
        }

        private const val SEPARATOR = " · "
    }
}

/** One Queue row. [trailing] is the duration when one is known, and the source otherwise. */
internal data class QueueItem(
    val url: String,
    val title: String,
    val trailing: String,
    val artwork: Artwork
) {
    companion object {
        fun of(context: Context, item: BrowserPlayQueue.Item): QueueItem {
            val fallbackLabel = HomeDashboardSource.label(context, item.url)
            return QueueItem(
                url = item.url,
                title = item.title.ifBlank { BridgeSource(item.url).displayHost },
                // The queue holds addresses, not media: a duration is only there when whoever
                // queued the item already knew one. Showing the source beats showing "0:00".
                trailing = HomeDashboardClock.duration(item.durationMs) ?: fallbackLabel,
                artwork = HomeDashboardSource.artwork(item.url)
            )
        }
    }
}

/**
 * What a URL is, as far as the dashboard needs to draw it: a label for the source line and the
 * glyph/accent its fallback artwork uses.
 *
 * Only distinctions the app can actually make from the address are made. Everything that is not a
 * YouTube front end is "the host it came from", which is honest and is already how
 * [BridgeSource.displayHost] labels a row everywhere else.
 */
internal object HomeDashboardSource {
    fun label(context: Context, url: String): String = when {
        url.isBlank() -> ""
        YouTubeUrls.isYouTubeMusic(url) -> HomeSection.YOUTUBE_MUSIC.title(context)
        YouTubeUrls.isYouTube(url) -> HomeSection.YOUTUBE.title(context)
        else -> BridgeSource(url).displayHost
    }

    fun artwork(url: String): Artwork = when {
        YouTubeUrls.isYouTubeMusic(url) ->
            Artwork(HomeMenuGlyph.YOUTUBE_MUSIC, HomeDashboardTheme.ACCENT_YOUTUBE_MUSIC, CarThumbnails.url(url))
        YouTubeUrls.isYouTube(url) ->
            Artwork(HomeMenuGlyph.YOUTUBE, HomeDashboardTheme.ACCENT_YOUTUBE, CarThumbnails.url(url))
        else -> Artwork(HomeMenuGlyph.GLOBE, HomeDashboardTheme.ACCENT_WEB, CarThumbnails.url(url))
    }
}

/** Clock-style formatting for playback positions. Pure, so a JVM test covers the edges. */
internal object HomeDashboardClock {
    /** `12:34`, or `1:02:34` once an hour is reached. Null for a duration nobody reported. */
    fun duration(ms: Long): String? = if (ms <= 0L) null else clock(ms)

    /** `12:34 / 31:20`, or just the position when the duration is unknown. */
    fun elapsed(positionMs: Long, durationMs: Long): String {
        val position = clock(positionMs.coerceAtLeast(0L))
        val total = duration(durationMs) ?: return position
        return "$position / $total"
    }

    fun clock(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) + 500L) / 1000L
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }
}
