package dev.autobridge.entertainment

import dev.autobridge.core.model.Feature

/** Explicit boundary between browser pages, audio continuity, and parked video rendering. */
enum class ContentKind(val requiredFeature: Feature) {
    WEB(Feature.BROWSER),
    AUDIO(Feature.MEDIA),
    VIDEO(Feature.VIDEO)
}

/** Pure content-kind inference used by the entertainment picker and persistence layer. */
object ContentKindResolver {
    private val audioExtensions = setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "flac", "opus")
    private val videoExtensions = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "ts", "3gp")

    fun classify(uri: String, mimeType: String? = null): ContentKind? {
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase()
        if (mime?.startsWith("audio/") == true) return ContentKind.AUDIO
        if (mime?.startsWith("video/") == true) return ContentKind.VIDEO

        val path = uri.substringBefore('#').substringBefore('?').lowercase()
        val extension = path.substringAfterLast('.', missingDelimiterValue = "")
        return when {
            extension in audioExtensions -> ContentKind.AUDIO
            extension in videoExtensions || extension == "m3u8" || extension == "mpd" -> ContentKind.VIDEO
            path.startsWith("https://") || path.startsWith("http://") -> ContentKind.WEB
            else -> null
        }
    }
}
