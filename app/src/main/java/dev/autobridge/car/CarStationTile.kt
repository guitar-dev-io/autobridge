package dev.autobridge.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * The radio grid's cell art on the car: every station on the same rounded card, so a page of
 * stations reads as one set rather than a few logos among rows of the same speaker glyph.
 *
 *  - A station with a logo gets it filling the card edge to edge when it is about square, as
 *    the radio design draws it; a wide or tall one is fitted on a light card instead of cropped,
 *    which also keeps dark and transparent logos readable on the host's dark background.
 *  - A station without one gets a coloured card with its frequency ("90.5", "101") or, failing
 *    that, the first letters of its name. The colour comes from the name, so a station keeps its
 *    colour from one visit to the next and neighbours rarely match.
 *
 * The station on the air carries the radio accent as a ring, the design's highlighted cell.
 *
 * Tiles are [SIZE_PX] square: the host scales a grid image up into its own slot, and the binder
 * budget [CarChannelLogos] keeps applies to these bitmaps as much as to bare logos.
 */
internal object CarStationTile {
    const val SIZE_PX = 64

    private val palette = intArrayOf(
        0xFFE0683C.toInt(), 0xFFD9475F.toInt(), 0xFF9A55D6.toInt(), 0xFF4F6BE0.toInt(),
        0xFF2B8FD6.toInt(), 0xFF199E8E.toInt(), 0xFF3C9E4F.toInt(), 0xFFC2852A.toInt()
    )

    private val frequency = Regex("""\b(\d{2,3}(?:[.,]\d{1,2})?)\b""")

    /** What a logo-less tile says: the frequency in the name, else up to two initials. */
    fun label(title: String): String {
        val name = title.trim()
        frequency.find(name)?.let { match ->
            val value = match.groupValues[1].replace(',', '.')
            val whole = value.substringBefore('.').toIntOrNull()
            // 87.5–108 MHz FM, or a bare channel number such as "101".
            if (whole != null && whole in 80..199) return value
        }
        val words = name.split(' ', '-', '_', '.', '|', '(').filter { w -> w.firstOrNull()?.isLetterOrDigit() == true }
        val letters = when {
            words.size >= 2 && words[0].first().isLatinLetter() && words[1].first().isLatinLetter() ->
                "${words[0].first()}${words[1].first()}"
            words.isNotEmpty() -> words[0].first().toString()
            else -> ""
        }
        return letters.uppercase().ifEmpty { "FM" }
    }

    fun colorFor(title: String): Int = palette[Math.floorMod(title.trim().lowercase().hashCode(), palette.size)]

    fun placeholder(title: String, playing: Boolean = false): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorFor(title) }
        canvas.drawRoundRect(cardRect(), RADIUS, RADIUS, card)
        // A soft highlight across the top so the flat colour has some depth.
        card.color = 0x22FFFFFF
        canvas.drawRoundRect(RectF(0f, 0f, SIZE_PX.toFloat(), SIZE_PX * 0.5f), RADIUS, RADIUS, card)

        val text = label(title)
        val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = when {
                text.length <= 2 -> SIZE_PX * 0.42f
                text.length == 3 -> SIZE_PX * 0.34f
                else -> SIZE_PX * 0.28f
            }
        }
        val baseline = SIZE_PX / 2f - (pen.descent() + pen.ascent()) / 2f
        canvas.drawText(text, SIZE_PX / 2f, baseline, pen)
        if (playing) ring(canvas)
        return bitmap
    }

    /** [logo] filling the card when about square, else fitted on the light card. */
    fun framed(logo: Bitmap, playing: Boolean = false): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF4F5F7.toInt() }
        canvas.drawRoundRect(cardRect(), RADIUS, RADIUS, card)
        val aspect = logo.width / logo.height.toFloat()
        val square = aspect in 0.8f..1.25f
        val inner = if (square) SIZE_PX.toFloat() else SIZE_PX - 2f * PADDING
        val scale = if (square) maxOf(inner / logo.width, inner / logo.height)
        else minOf(inner / logo.width, inner / logo.height)
        val w = logo.width * scale
        val h = logo.height * scale
        val left = (SIZE_PX - w) / 2f
        val top = (SIZE_PX - h) / 2f
        canvas.save()
        canvas.clipPath(android.graphics.Path().apply { addRoundRect(cardRect(), RADIUS, RADIUS, android.graphics.Path.Direction.CW) })
        canvas.drawBitmap(logo, null, RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
        if (playing) ring(canvas)
        return bitmap
    }

    /**
     * A grid cell that is not a station (search, podcasts, all stations): its [glyph] in white on
     * a quiet dark card, so it sits in the same set of rounded cards as the stations around it.
     */
    fun action(glyph: android.graphics.drawable.Drawable): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF262A31.toInt() }
        canvas.drawRoundRect(cardRect(), RADIUS, RADIUS, card)
        val size = (SIZE_PX * 0.44f).toInt()
        val start = (SIZE_PX - size) / 2
        val icon = glyph.mutate()
        icon.setTint(0xFFF3F5F7.toInt())
        icon.setBounds(start, start, start + size, start + size)
        icon.draw(canvas)
        return bitmap
    }

    /** The radio accent ring around the tile of the station on the air. */
    private fun ring(canvas: Canvas) {
        val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = RING
            color = ACCENT_RADIO
        }
        val inset = RING / 2f
        canvas.drawRoundRect(RectF(inset, inset, SIZE_PX - inset, SIZE_PX - inset), RADIUS - inset, RADIUS - inset, pen)
    }

    private fun cardRect() = RectF(0f, 0f, SIZE_PX.toFloat(), SIZE_PX.toFloat())

    private fun Char.isLatinLetter() = this in 'a'..'z' || this in 'A'..'Z'

    private const val RADIUS = 14f
    private const val PADDING = 7
    private const val RING = 4f
    private const val ACCENT_RADIO = 0xFFFFB35C.toInt()
}
