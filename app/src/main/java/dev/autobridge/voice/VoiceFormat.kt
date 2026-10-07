package dev.autobridge.voice

import java.util.Locale

/** Number formatting the voice screens share; locale-independent digits, like the rest of the app. */
object VoiceFormat {
    /** Decimal megabytes with one decimal, as model hosts publish sizes: 81768585 -> "81.8 MB". */
    fun size(bytes: Long): String = when {
        bytes <= 0 -> "0 MB"
        bytes < 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0).let { if (it == "0.0 MB") "0.1 MB" else it }
        bytes >= 1_000_000_000 -> String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    }

    /** Milliseconds as seconds with one or two decimals: 3200 -> "3.2", 840 -> "0.84". */
    fun seconds(ms: Long): String =
        if (ms < 1_000) String.format(Locale.US, "%.2f", ms / 1000.0) else String.format(Locale.US, "%.1f", ms / 1000.0)

    fun ratio(value: Double): String = String.format(Locale.US, "%.2f", value)
}
