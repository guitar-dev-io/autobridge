package dev.autobridge.library

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * The icons of the Streaming grid, drawn here: a rounded square in the site's colour with a short
 * mark on it. The sites' own logos are not bundled (they are their owners' marks) and are not
 * fetched either, which would tell a third party which sites the driver watches.
 */
object StreamingIcons {
    /** What an icon shows: the mark (a letter or two, or a character) and its colour. */
    data class Style(val glyph: String, val color: Int)

    private val CATALOG: Map<String, Style> = mapOf(
        "YouTube" to Style("▶", 0xFFE62117.toInt()),
        "TikTok" to Style("♪", 0xFF111111.toInt()),
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
        "Twitch" to Style("T", 0xFF9146FF.toInt()),
        "Bilibili" to Style("bili", 0xFFFB7299.toInt()),
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
    fun bitmap(style: Style, sizePx: Int, starred: Boolean = false): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val size = sizePx.toFloat()
        val radius = size * 0.22f
        val box = RectF(size * 0.04f, size * 0.04f, size * 0.96f, size * 0.96f)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.color }
        canvas.drawRoundRect(box, radius, radius, fill)
        // A faint rim so a dark icon still reads against a dark screen.
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = size * 0.02f
            color = 0x40FFFFFF
        }
        canvas.drawRoundRect(box, radius, radius, rim)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = size * 0.5f
        }
        // Shrink the mark until it fits inside the square.
        while (text.measureText(style.glyph) > size * 0.74f && text.textSize > size * 0.12f) text.textSize -= size * 0.02f
        val baseline = size / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(style.glyph, size / 2f, baseline, text)

        if (starred) {
            val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111111.toInt() }
            canvas.drawCircle(size * 0.80f, size * 0.20f, size * 0.17f, badge)
            val star = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFC107.toInt()
                textAlign = Paint.Align.CENTER
                textSize = size * 0.26f
            }
            canvas.drawText("★", size * 0.80f, size * 0.20f - (star.descent() + star.ascent()) / 2f, star)
        }
        return bitmap
    }
}
