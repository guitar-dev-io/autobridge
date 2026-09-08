package dev.autobridge.media

/**
 * Pure classification of a media URI/path into the source type ExoPlayer needs, kept separate
 * from the Android [MediaPlaybackService]/[MediaPlaybackClient] glue so the branching logic is
 * unit-testable without a real player.
 *
 * ExoPlayer can infer the type from the URI in most cases, but HLS (`.m3u8`) and DASH (`.mpd`)
 * adaptive manifests are detected by extension/query so the right `MediaSource.Factory` is used,
 * and local paths are normalized to `file://` URIs so they resolve the same way as remote ones.
 */
object MediaSourceResolver {

    enum class SourceType {
        /** Progressive remote file (mp3, mp4, etc.) or a generic http(s) URL. */
        PROGRESSIVE,

        /** HLS adaptive stream (`.m3u8`). */
        HLS,

        /** DASH adaptive stream (`.mpd`). */
        DASH,

        /** On-device file. */
        LOCAL
    }

    data class Resolved(val uri: String, val type: SourceType)

    /**
     * Classifies [input] and returns a normalized URI plus its [SourceType], or null if the
     * input is blank. A local filesystem path is turned into a `file://` URI.
     */
    fun resolve(input: String?): Resolved? {
        val raw = input?.trim().orEmpty()
        if (raw.isEmpty()) return null

        val isRemote = raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true)
        val isFileScheme = raw.startsWith("file://", ignoreCase = true)
        val isContentScheme = raw.startsWith("content://", ignoreCase = true)

        val normalized = when {
            isRemote || isFileScheme || isContentScheme -> raw
            // Bare filesystem path -> file:// URI.
            raw.startsWith("/") -> "file://$raw"
            else -> raw
        }

        val isLocal = isFileScheme || isContentScheme || normalized.startsWith("file://", ignoreCase = true)

        val type = when {
            hasStreamExtension(normalized, "m3u8") -> SourceType.HLS
            hasStreamExtension(normalized, "mpd") -> SourceType.DASH
            isLocal -> SourceType.LOCAL
            else -> SourceType.PROGRESSIVE
        }

        return Resolved(normalized, type)
    }

    /**
     * True if [uri]'s path (ignoring any `?query`/`#fragment`) ends with `.[extension]`,
     * case-insensitively. Adaptive manifests are often served with query strings appended.
     */
    private fun hasStreamExtension(uri: String, extension: String): Boolean {
        val withoutFragment = uri.substringBefore('#')
        val path = withoutFragment.substringBefore('?')
        return path.endsWith(".$extension", ignoreCase = true)
    }
}
