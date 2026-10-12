package dev.autobridge.iptv

import android.content.Context
import android.text.format.DateFormat
import dev.autobridge.R
import java.util.Date

/**
 * How the guide and the broken-channel memory read in a row, shared by the phone and the head unit
 * so the two never word the same fact differently. Times follow the device's 12/24-hour setting.
 */
object IptvGuideText {
    /** "▶ Evening News · until 19:00", or null when nothing is on now. */
    fun now(context: Context, guide: IptvNowNext?): String? {
        val now = guide?.now ?: return null
        return context.getString(R.string.guide_now_until, now.title, time(context, now.stopMs))
    }

    /** "Next 19:00 · Drama", or null when the guide names nothing after now. */
    fun next(context: Context, guide: IptvNowNext?): String? {
        val next = guide?.next ?: return null
        return context.getString(R.string.guide_next, time(context, next.startMs), next.title)
    }

    /** "Not working · 3 h ago", for a channel whose last attempt failed. */
    fun broken(context: Context, record: IptvHealthStore.Record, nowMs: Long = System.currentTimeMillis()): String =
        context.getString(R.string.health_not_working, ago(context, nowMs - record.lastFailMs))

    fun smartLabel(context: Context, kind: IptvSmartGroups.Kind): String = context.getString(
        when (kind) {
            IptvSmartGroups.Kind.NEWS -> R.string.smart_news
            IptvSmartGroups.Kind.SPORTS -> R.string.smart_sports
            IptvSmartGroups.Kind.KIDS -> R.string.smart_kids
            IptvSmartGroups.Kind.MOVIES -> R.string.smart_movies
            IptvSmartGroups.Kind.MUSIC -> R.string.smart_music
            IptvSmartGroups.Kind.DOCUMENTARY -> R.string.smart_documentary
            IptvSmartGroups.Kind.RELIGION -> R.string.smart_religion
            IptvSmartGroups.Kind.SHOPPING -> R.string.smart_shopping
        }
    )

    private fun time(context: Context, ms: Long): String = DateFormat.getTimeFormat(context).format(Date(ms))

    private fun ago(context: Context, elapsedMs: Long): String {
        val minutes = (elapsedMs / 60_000L).coerceAtLeast(1L)
        return when {
            minutes < 60 -> context.getString(R.string.health_ago_minutes, minutes.toInt())
            minutes < 48 * 60 -> context.getString(R.string.health_ago_hours, (minutes / 60).toInt())
            else -> context.getString(R.string.health_ago_days, (minutes / (24 * 60)).toInt())
        }
    }
}
