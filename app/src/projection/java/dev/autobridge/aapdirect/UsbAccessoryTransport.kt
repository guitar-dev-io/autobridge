package dev.autobridge.aapdirect

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import dev.autobridge.aap.AapTransport
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The car as a USB host, this phone as its Android Open Accessory. The car switches the phone into
 * accessory mode with the identity "Android" / "Android Auto"; the accessory filter in the
 * manifest is what routes that to AutoBridge rather than to Google's Android Auto app.
 */
class UsbAccessoryTransport private constructor(
    private val descriptor: ParcelFileDescriptor,
    accessory: UsbAccessory,
) : AapTransport {
    override val description: String =
        "USB accessory ${accessory.manufacturer} / ${accessory.model} ${accessory.version.orEmpty()}".trim()

    override val input: InputStream = ChunkedInput(FileInputStream(descriptor.fileDescriptor))
    override val output: OutputStream = FileOutputStream(descriptor.fileDescriptor)

    override fun close() {
        runCatching { descriptor.close() }
    }

    /**
     * Reads from the accessory endpoint in whole 16 kB requests. A bulk read shorter than the
     * packet the car sent fails (or loses the rest of it) on many kernels, so small reads are
     * served from this buffer instead of going to the endpoint.
     */
    private class ChunkedInput(private val raw: FileInputStream) : InputStream() {
        private val buffer = ByteArray(16384)
        private var start = 0
        private var end = 0

        override fun read(): Int {
            if (!fill()) return -1
            return buffer[start++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, end - start)
            System.arraycopy(buffer, start, b, off, n)
            start += n
            return n
        }

        private fun fill(): Boolean {
            while (start >= end) {
                val n = try {
                    raw.read(buffer, 0, buffer.size)
                } catch (e: IOException) {
                    throw IOException("USB read failed: ${e.message}", e)
                }
                if (n < 0) return false
                start = 0
                end = n
            }
            return true
        }

        override fun close() = raw.close()
    }

    companion object {
        fun open(manager: UsbManager, accessory: UsbAccessory): UsbAccessoryTransport? {
            val descriptor = manager.openAccessory(accessory) ?: return null
            return UsbAccessoryTransport(descriptor, accessory)
        }

        /** The Android Auto accessory identity a head unit announces. */
        fun isAndroidAuto(accessory: UsbAccessory): Boolean =
            accessory.manufacturer == "Android" && accessory.model == "Android Auto"
    }
}
