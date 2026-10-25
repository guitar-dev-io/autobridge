package dev.autobridge.library

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import dev.autobridge.design.ThemeHolder
import dev.autobridge.ui.AppFonts

/**
 * The icons of the Streaming grid, drawn here: a rounded square in the site's colour with a short
 * mark on it. The sites' own logos are not bundled (they are their owners' marks) and are not
 * fetched either, which would tell a third party which sites the driver watches.
 */
object StreamingIcons {
    /** What an icon shows: the mark (a letter or two, or a character) and its colour. */
    data class Style(val glyph: String, val color: Int, val onColor: Int = 0xFFFFFFFF.toInt())

    private val CATALOG: Map<String, Style> = mapOf(
        "Netflix" to Style("N", 0xFF141414.toInt(), onColor = 0xFFE50914.toInt()),
        "Disney+" to Style("D+", 0xFF0B2F6B.toInt()),
        "Prime Video" to Style("prime", 0xFF1A98FF.toInt()),
        "HBO Max" to Style("HBO", 0xFF111111.toInt()),
        "Viu" to Style("viu", 0xFFFFB400.toInt(), onColor = 0xFF1A1A1A.toInt()),
        "YouTube" to Style("▶", 0xFFE62117.toInt()),
        "TikTok" to Style("♪", 0xFF111111.toInt()),
        "Facebook Watch" to Style("f", 0xFF1877F2.toInt()),
        "Dailymotion" to Style("d", 0xFF0066DC.toInt()),
        "Vimeo" to Style("v", 0xFF1AB7EA.toInt()),
        "Tencent Video" to Style("腾", 0xFF1E88E5.toInt()),
        "WeTV" to Style("WE", 0xFF0EA5E9.toInt()),
        "iQIYI" to Style("iQ", 0xFF00A806.toInt()),
        "Youku" to Style("优", 0xFF2F86F6.toInt()),
        "Mango TV" to Style("芒", 0xFFFF6A13.toInt()),
        "CCTV" to Style("CCTV", 0xFF8B1A1A.toInt()),
        "Douyin" to Style("抖", 0xFF161823.toInt()),
        "Xiaohongshu" to Style("小红书", 0xFFFF2442.toInt()),
        "Internet Archive: Feature Films" to Style("IA", 0xFF4B5563.toInt()),
        "YouTube Music" to Style("♫", 0xFFD93025.toInt()),
        "SoundCloud" to Style("☁", 0xFFFF5500.toInt()),
        "Mixcloud" to Style("M", 0xFF5000FF.toInt()),
        "Radio Garden" to Style("◉", 0xFF2E7D32.toInt()),
        "YouTube Live" to Style("●", 0xFFCC0000.toInt()),
        "Twitch" to Style("T", 0xFF9146FF.toInt()),
        "Kick" to Style("K", 0xFF1B8F0A.toInt()),
        "Bilibili" to Style("bili", 0xFFFB7299.toInt()),
        "Muse Asia" to Style("M", 0xFFE4007F.toInt()),
        "Ani-One Asia" to Style("A1", 0xFFF39800.toInt()),
    )

    private val PALETTE = intArrayOf(
        0xFF6D28D9.toInt(), 0xFF0F766E.toInt(), 0xFFB45309.toInt(), 0xFFBE123C.toInt(),
        0xFF1D4ED8.toInt(), 0xFF4D7C0F.toInt(), 0xFF9D174D.toInt(), 0xFF0E7490.toInt(),
    )

    val ADD = Style("+", 0xFF374151.toInt())
    val MANAGE = Style("★", 0xFF374151.toInt())

    /** True when [link] has an icon of its own in the catalog (a new site must be given one). */
    fun hasOwnStyle(link: StreamingLink): Boolean = CATALOG.containsKey(link.title)

    /**
     * The icon style for a site: the catalog's own for a known address, otherwise the first letter
     * of its name on a colour chosen from its address, so the same site always looks the same.
     */
    fun styleFor(title: String, url: String): Style {
        StreamingLinks.all.firstOrNull { it.url == url }?.let { link -> CATALOG[link.title]?.let { return it } }
        val letter = title.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•"
        return Style(letter, PALETTE[Math.floorMod(url.hashCode(), PALETTE.size)])
    }

    /** Draws [style] as a square bitmap of [sizePx]; [starred] adds a small gold star in the corner. */
    fun bitmap(style: Style, sizePx: Int, starred: Boolean = false): Bitmap =
        draw(style, sizePx, sizePx, starred, radiusFraction = 0.22f, markFraction = 0.5f, insetFraction = 0.04f)

    /**
     * Draws [style] as a wide card of [widthPx] x [heightPx], a brand colour with its mark large on
     * it: the big tiles of the Video group, as in a streaming launcher's grid.
     */
    fun cardBitmap(style: Style, widthPx: Int, heightPx: Int, starred: Boolean = false): Bitmap =
        draw(style, widthPx, heightPx, starred, radiusFraction = 0.12f, markFraction = 0.52f, insetFraction = 0.015f)

    private fun draw(
        style: Style, widthPx: Int, heightPx: Int, starred: Boolean,
        radiusFraction: Float, markFraction: Float, insetFraction: Float
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val w = widthPx.toFloat()
        val h = heightPx.toFloat()
        val short = minOf(w, h)
        val radius = short * radiusFraction
        val inset = short * insetFraction
        val box = RectF(inset, inset, w - inset, h - inset)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.color }
        canvas.drawRoundRect(box, radius, radius, fill)
        // A faint rim so a dark icon still reads against a dark screen (and a pale one on Light).
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = short * 0.02f
            color = if (ThemeHolder.palette.isLight) 0x26000000 else 0x40FFFFFF
        }
        canvas.drawRoundRect(box, radius, radius, rim)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.onColor
            typeface = AppFonts.bold()
            textAlign = Paint.Align.CENTER
            textSize = short * markFraction
        }
        // Shrink the mark until it fits inside the shape.
        while (text.measureText(style.glyph) > w * 0.74f && text.textSize > short * 0.12f) text.textSize -= short * 0.02f
        val baseline = h / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(style.glyph, w / 2f, baseline, text)

        if (starred) {
            val cx = w - short * 0.2f
            val cy = short * 0.2f
            val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ThemeHolder.palette.background }
            canvas.drawCircle(cx, cy, short * 0.17f, badge)
            val star = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFC107.toInt()
                textAlign = Paint.Align.CENTER
                textSize = short * 0.26f
            }
            canvas.drawText("★", cx, cy - (star.descent() + star.ascent()) / 2f, star)
        }
        return bitmap
    }
}
