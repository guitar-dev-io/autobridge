package dev.autobridge.desktop.keyboard

/**
 * Whether a cursor image is the text (I-beam) cursor. Chrome reports field focus to nobody here,
 * but it shows the I-beam while the pointer is over text it can edit or select, so after a tap
 * the cursor under the pointer is the one hint that a text field was tapped. Pure, on ARGB pixels.
 *
 * The I-beam's drawn part (alpha above half) is tall and narrow, with the hotspot inside it near
 * its vertical middle. The arrow and the hand have their hotspot at the top; crosshair, move and
 * not-allowed are square; resize arrows across are wide. Theme cursors are square images with the
 * shape drawn inside, so the drawn part is measured, not the image size.
 */
object TextCursor {
    fun isText(width: Int, height: Int, xhot: Int, yhot: Int, pixels: IntArray): Boolean {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return false
        var left = width
        var right = -1
        var top = height
        var bottom = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if ((pixels[row + x] ushr 24) > 0x80) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < 0) return false
        val w = right - left + 1
        val h = bottom - top + 1
        if (h < MIN_HEIGHT || h * 10 < w * 16) return false
        if (xhot < left - 1 || xhot > right + 1) return false
        val middle = (top + bottom) / 2f
        return kotlin.math.abs(yhot - middle) <= h / 4f
    }

    private const val MIN_HEIGHT = 8
}
