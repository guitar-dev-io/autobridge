package dev.autobridge.support

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Draws a QR code on the device, so a support link's QR never has to be fetched or bundled. */
object QrCode {

    /**
     * The channel's own QR artwork when one is bundled (the official Buy Me a Coffee code with its
     * cup logo, the PromptPay code), else null and the caller draws one with [bitmap]. Bundled, not
     * fetched, so the code cannot be swapped out from under the user.
     */
    fun bundled(kind: SupportChannels.Kind): Int? = when (kind) {
        SupportChannels.Kind.BUY_ME_A_COFFEE -> dev.autobridge.R.drawable.bmc_qr
        SupportChannels.Kind.PROMPTPAY -> dev.autobridge.R.drawable.promptpay_qr
        else -> null
    }

    /**
     * Bundled artwork [res] flattened onto white with a small quiet zone. The PNGs have transparent
     * grounds, which a dark car theme would turn into black-on-black.
     */
    fun bundledOnWhite(context: android.content.Context, res: Int, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(Color.WHITE)
        val inset = size / 16
        context.getDrawable(res)?.apply {
            setBounds(inset, inset, size - inset, size - inset)
            draw(canvas)
        }
        return out
    }

    /** A square [size]-pixel QR of [text], dark modules on a light ground with a one-module margin. */
    fun bitmap(text: String, size: Int, dark: Int = Color.BLACK, light: Int = Color.WHITE): Bitmap {
        val matrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
        )
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height) { i -> if (matrix[i % width, i / width]) dark else light }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
