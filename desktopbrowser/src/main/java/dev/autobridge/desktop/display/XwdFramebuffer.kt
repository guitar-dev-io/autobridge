package dev.autobridge.desktop.display

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Xvfb's screen, read straight from memory.
 *
 * Started with `-fbdir`, Xvfb keeps its framebuffer in a memory-mapped file in XWD format; this
 * maps the same file read-only, so a frame is never copied through a pipe or socket. Layout,
 * checked against a real Xvfb 21 (Debian 13) dump: a big-endian XWD header (100 bytes + window
 * name, `header_size` in total), `ncolors` 12-byte colormap entries, then the pixels — 32 bits per
 * pixel, little-endian, i.e. bytes B,G,R,X per pixel.
 */
class XwdFramebuffer private constructor(val header: Header, private val mapped: MappedByteBuffer) {
    data class Header(
        val headerSize: Int,
        val width: Int,
        val height: Int,
        val depth: Int,
        val bitsPerPixel: Int,
        val bytesPerLine: Int,
        val byteOrder: Int,
        val colormapEntries: Int,
    ) {
        val pixelOffset: Int get() = headerSize + colormapEntries * 12
        val pixelBytes: Int get() = bytesPerLine * height
    }

    /** A view of the pixel rows, position 0. Shares memory with the live framebuffer. */
    fun pixels(): ByteBuffer {
        val view = mapped.duplicate()
        view.position(header.pixelOffset)
        view.limit(header.pixelOffset + header.pixelBytes)
        return view.slice().order(ByteOrder.LITTLE_ENDIAN)
    }

    companion object {
        fun open(file: File): XwdFramebuffer {
            RandomAccessFile(file, "r").use { raf ->
                val size = raf.length()
                if (size < 100) throw IOException("framebuffer not ready")
                val map = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
                val header = parseHeader(map)
                if (header.pixelOffset + header.pixelBytes > size) throw IOException("framebuffer truncated")
                return XwdFramebuffer(header, map)
            }
        }

        fun parseHeader(bytes: ByteBuffer): Header {
            val b = bytes.duplicate().order(ByteOrder.BIG_ENDIAN)
            fun field(index: Int) = b.getInt(index * 4)
            val header = Header(
                headerSize = field(0),
                width = field(4),
                height = field(5),
                depth = field(3),
                bitsPerPixel = field(11),
                bytesPerLine = field(12),
                byteOrder = field(7),
                colormapEntries = field(19),
            )
            if (field(1) != 7) throw IOException("not an XWD v7 framebuffer")
            if (header.bitsPerPixel != 32) throw IOException("unsupported ${header.bitsPerPixel} bpp framebuffer")
            if (header.width <= 0 || header.height <= 0 || header.bytesPerLine < header.width * 4) throw IOException("bad framebuffer geometry")
            return header
        }
    }
}
