package dev.autobridge.library

import androidx.annotation.StringRes
import dev.autobridge.R

/** A group's name in the UI language ([StreamingGroup.title] is the English one); one list for the phone and the car. */
@StringRes
fun StreamingGroup.labelRes(): Int = when (this) {
    StreamingGroup.PREMIUM -> R.string.streaming_group_premium
    StreamingGroup.VIDEO -> R.string.streaming_group_video
    StreamingGroup.SOCIAL -> R.string.streaming_group_social
    StreamingGroup.MOVIES -> R.string.streaming_group_movies
    StreamingGroup.MUSIC -> R.string.streaming_group_music
    StreamingGroup.LIVE -> R.string.streaming_group_live
    StreamingGroup.ANIME -> R.string.streaming_group_anime
}
