package dev.autobridge.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat

/** Square artwork for GridTemplate's 128dp image slot. The host owns its size and touch target. */
internal object DashboardArtwork {
    enum class Kind {
        BROWSER, MIRROR, MEDIA, AGENT, QUICK_LAUNCH, RECENT, MORE, YOUTUBE,
        // Content sections shared with the phone home grid.
        TV, RADIO, YOUTUBE_MUSIC, YOUTUBE_KIDS, FOLDERS, FAVORITES, PLAYLISTS, GALLERY, SETTINGS,
        WEATHER
    }

    private val icons = mutableMapOf<Pair<Kind, Boolean>, CarIcon>()

    @Synchronized
    fun icon(kind: Kind, compact: Boolean = false): CarIcon = icons.getOrPut(kind to compact) {
        // A wide bitmap was scaled into a short thumbnail by DHU, making the glyph tiny.
        // Square transparent art fills the host's 128dp image slot and stays below Binder limits.
        val width = 128
        val height = 128
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (compact) {
            val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF202228.toInt() }
            canvas.drawRoundRect(RectF(2f, 2f, 126f, 126f), 12f, 12f, card)
            card.style = Paint.Style.STROKE
            card.strokeWidth = 1.5f
            card.color = 0xFF50525A.toInt()
            canvas.drawRoundRect(RectF(2f, 2f, 126f, 126f), 12f, 12f, card)
        }
        canvas.scale(width / 152f, height / 152f)
        canvas.translate(-84f, 0f)
        val glyphScale = if (compact) 0.85f else 1.25f
        canvas.scale(glyphScale, glyphScale, 160f, 76f)
        val accent = when (kind) {
            Kind.BROWSER -> 0xFF70ACFF.toInt()
            Kind.MIRROR -> 0xFFB56DFF.toInt()
            Kind.MEDIA -> 0xFFAF80F8.toInt()
            Kind.AGENT -> 0xFF7CE1CB.toInt()
            Kind.QUICK_LAUNCH -> 0xFF86E3AF.toInt()
            Kind.TV, Kind.YOUTUBE_MUSIC, Kind.YOUTUBE_KIDS -> 0xFFFF7A7A.toInt()
            Kind.RADIO -> 0xFFFFC46B.toInt()
            Kind.FOLDERS, Kind.PLAYLISTS -> 0xFF86E3AF.toInt()
            Kind.FAVORITES -> 0xFFFF8FB1.toInt()
            Kind.GALLERY -> 0xFF7CE1CB.toInt()
            Kind.RECENT, Kind.MORE, Kind.YOUTUBE, Kind.SETTINGS -> 0xFFD8E3F7.toInt()
            Kind.WEATHER -> 0xFF6BC5FF.toInt()
        }
        val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (compact) 0xFFF5F5F7.toInt() else accent
            style = Paint.Style.STROKE
            strokeWidth = 6f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        when (kind) {
            Kind.YOUTUBE -> {
                pen.style = Paint.Style.FILL
                canvas.drawRoundRect(RectF(121f, 49f, 199f, 103f), 12f, 12f, pen)
                pen.color = 0xFF202228.toInt()
                val play = Path().apply {
                    moveTo(152f, 62f); lineTo(152f, 90f); lineTo(176f, 76f); close()
                }
                canvas.drawPath(play, pen)
            }
            Kind.MORE -> {
                pen.style = Paint.Style.FILL
                for (x in listOf(134f, 160f, 186f)) canvas.drawCircle(x, 76f, 6f, pen)
            }
            Kind.BROWSER -> {
                canvas.drawCircle(160f, 76f, 37f, pen)
                canvas.drawOval(RectF(144f, 39f, 176f, 113f), pen)
                canvas.drawLine(123f, 76f, 197f, 76f, pen)
                canvas.drawLine(130f, 56f, 190f, 56f, pen)
                canvas.drawLine(130f, 96f, 190f, 96f, pen)
            }
            Kind.MIRROR -> {
                // The reference uses a display rather than a phone silhouette.
                canvas.drawRoundRect(RectF(124f, 43f, 196f, 96f), 5f, 5f, pen)
                canvas.drawLine(160f, 97f, 160f, 109f, pen)
                canvas.drawLine(145f, 110f, 175f, 110f, pen)
            }
            Kind.MEDIA -> {
                pen.style = Paint.Style.FILL
                val triangle = Path().apply {
                    moveTo(142f, 44f); lineTo(142f, 108f); lineTo(194f, 76f); close()
                }
                canvas.drawPath(triangle, pen)
            }
            Kind.AGENT -> {
                pen.style = Paint.Style.FILL
                val sparkle = Path().apply {
                    moveTo(157f, 35f); lineTo(166f, 64f); lineTo(195f, 74f)
                    lineTo(166f, 84f); lineTo(157f, 114f); lineTo(148f, 84f)
                    lineTo(119f, 74f); lineTo(148f, 64f); close()
                }
                canvas.drawPath(sparkle, pen)
                canvas.drawCircle(197f, 46f, 5f, pen)
            }
            Kind.QUICK_LAUNCH -> {
                // Four rounded app tiles echo the Apps icon in the reference.
                pen.style = Paint.Style.FILL
                for (x in listOf(126f, 166f)) {
                    for (y in listOf(43f, 83f)) {
                        canvas.drawRoundRect(RectF(x, y, x + 28f, y + 28f), 6f, 6f, pen)
                    }
                }
            }
            Kind.TV -> {
                canvas.drawRoundRect(RectF(122f, 42f, 198f, 96f), 6f, 6f, pen)
                canvas.drawLine(142f, 108f, 178f, 108f, pen)
                canvas.drawLine(160f, 97f, 160f, 108f, pen)
            }
            Kind.RADIO -> {
                // Speaker cone plus two emission arcs.
                val cone = Path().apply {
                    moveTo(150f, 62f); lineTo(150f, 90f); lineTo(136f, 90f); lineTo(136f, 62f); close()
                }
                canvas.drawPath(cone, pen)
                canvas.drawLine(150f, 62f, 166f, 46f, pen)
                canvas.drawLine(150f, 90f, 166f, 106f, pen)
                canvas.drawLine(166f, 46f, 166f, 106f, pen)
                canvas.drawArc(RectF(166f, 56f, 190f, 96f), -70f, 140f, false, pen)
                canvas.drawArc(RectF(172f, 44f, 204f, 108f), -70f, 140f, false, pen)
            }
            Kind.YOUTUBE_MUSIC -> {
                pen.style = Paint.Style.FILL
                canvas.drawRoundRect(RectF(121f, 49f, 199f, 103f), 12f, 12f, pen)
                pen.color = 0xFF202228.toInt()
                pen.style = Paint.Style.STROKE
                canvas.drawCircle(152f, 88f, 8f, pen)
                canvas.drawLine(160f, 88f, 160f, 62f, pen)
                canvas.drawLine(160f, 62f, 178f, 66f, pen)
            }
            Kind.YOUTUBE_KIDS -> {
                pen.style = Paint.Style.FILL
                canvas.drawRoundRect(RectF(121f, 49f, 199f, 103f), 12f, 12f, pen)
                pen.color = 0xFF202228.toInt()
                canvas.drawCircle(146f, 70f, 6f, pen)
                canvas.drawCircle(174f, 70f, 6f, pen)
                pen.style = Paint.Style.STROKE
                canvas.drawArc(RectF(142f, 74f, 178f, 98f), 20f, 140f, false, pen)
            }
            Kind.FOLDERS -> {
                val folder = Path().apply {
                    moveTo(124f, 102f); lineTo(124f, 50f); lineTo(150f, 50f); lineTo(158f, 60f)
                    lineTo(196f, 60f); lineTo(196f, 102f); close()
                }
                canvas.drawPath(folder, pen)
            }
            Kind.FAVORITES -> {
                pen.style = Paint.Style.FILL
                val heart = Path().apply {
                    moveTo(160f, 106f)
                    cubicTo(120f, 82f, 122f, 50f, 143f, 48f)
                    cubicTo(153f, 47f, 158f, 55f, 160f, 60f)
                    cubicTo(162f, 55f, 167f, 47f, 177f, 48f)
                    cubicTo(198f, 50f, 200f, 82f, 160f, 106f)
                    close()
                }
                canvas.drawPath(heart, pen)
            }
            Kind.PLAYLISTS -> {
                canvas.drawLine(124f, 52f, 178f, 52f, pen)
                canvas.drawLine(124f, 72f, 178f, 72f, pen)
                canvas.drawLine(124f, 92f, 158f, 92f, pen)
                canvas.drawLine(188f, 46f, 188f, 96f, pen)
                pen.style = Paint.Style.FILL
                canvas.drawCircle(180f, 98f, 9f, pen)
            }
            Kind.GALLERY -> {
                canvas.drawRoundRect(RectF(122f, 44f, 198f, 108f), 6f, 6f, pen)
                val mountains = Path().apply {
                    moveTo(130f, 100f); lineTo(152f, 74f); lineTo(168f, 92f)
                    lineTo(178f, 82f); lineTo(192f, 100f)
                }
                canvas.drawPath(mountains, pen)
                pen.style = Paint.Style.FILL
                canvas.drawCircle(174f, 60f, 7f, pen)
            }
            Kind.WEATHER -> {
                // A sun peeking behind a cloud, drawn from the same primitives as the rest of the set.
                pen.style = Paint.Style.STROKE
                canvas.drawCircle(148f, 58f, 14f, pen)
                for (angle in 0 until 360 step 45) {
                    val radians = Math.toRadians(angle.toDouble())
                    canvas.drawLine(
                        148f + (16f * Math.cos(radians)).toFloat(),
                        58f + (16f * Math.sin(radians)).toFloat(),
                        148f + (22f * Math.cos(radians)).toFloat(),
                        58f + (22f * Math.sin(radians)).toFloat(),
                        pen
                    )
                }
                pen.style = Paint.Style.FILL
                val cloud = Path().apply {
                    addRoundRect(RectF(126f, 78f, 198f, 104f), 12f, 12f, Path.Direction.CW)
                }
                canvas.drawPath(cloud, pen)
                canvas.drawCircle(150f, 76f, 14f, pen)
                canvas.drawCircle(172f, 80f, 10f, pen)
            }
            Kind.SETTINGS -> {
                canvas.drawCircle(160f, 76f, 16f, pen)
                canvas.drawCircle(160f, 76f, 34f, pen)
                for (angle in 0 until 360 step 45) {
                    val radians = Math.toRadians(angle.toDouble())
                    canvas.drawLine(
                        160f + (34f * Math.cos(radians)).toFloat(),
                        76f + (34f * Math.sin(radians)).toFloat(),
                        160f + (44f * Math.cos(radians)).toFloat(),
                        76f + (44f * Math.sin(radians)).toFloat(),
                        pen
                    )
                }
            }
            Kind.RECENT -> {
                canvas.drawArc(RectF(124f, 40f, 196f, 112f), -155f, 290f, false, pen)
                val arrow = Path().apply {
                    moveTo(121f, 66f); lineTo(123f, 86f); lineTo(141f, 79f)
                }
                canvas.drawPath(arrow, pen)
                canvas.drawLine(160f, 76f, 160f, 56f, pen)
                canvas.drawLine(160f, 76f, 175f, 85f, pen)
            }
        }
        CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
    }
}
