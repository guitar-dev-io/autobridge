package dev.autobridge.voice

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The fixed-size header at the start of a whisper.cpp model file: a magic number and the eleven
 * hyper-parameters `whisper_model_load` reads before any tensor.
 *
 * Reading it is how a model is checked without loading it. A truncated download, an HTML error
 * page saved under the model's name, a `.en` model, or a file that is a different size class or
 * quantisation than the entry it was stored as - each fails here in microseconds, instead of in
 * native code after a few hundred megabytes of allocation.
 */
data class WhisperModelHeader(
    val magic: Int,
    val vocab: Int,
    val audioCtx: Int,
    val audioState: Int,
    val audioHead: Int,
    val audioLayers: Int,
    val textCtx: Int,
    val textState: Int,
    val textHead: Int,
    val textLayers: Int,
    val mels: Int,
    /** The stored ftype: `GGML_FTYPE_*` plus 1000 x the quantisation-format version. */
    val rawFtype: Int
) {
    val ftype: Int get() = rawFtype % QNT_VERSION_FACTOR

    val hasMagic: Boolean get() = magic == MAGIC

    enum class Problem {
        NOT_A_MODEL,
        NOT_MULTILINGUAL,
        WRONG_SIZE_CLASS,
        WRONG_QUANTIZATION,
        TRUNCATED
    }

    companion object {
        /** "ggml", little-endian. */
        const val MAGIC = 0x67676d6c
        const val BYTES = 4 * 12
        private const val QNT_VERSION_FACTOR = 1000

        fun parse(bytes: ByteArray): WhisperModelHeader? {
            if (bytes.size < BYTES) return null
            val buffer = ByteBuffer.wrap(bytes, 0, BYTES).order(ByteOrder.LITTLE_ENDIAN)
            val values = IntArray(12) { buffer.int }
            return WhisperModelHeader(
                magic = values[0],
                vocab = values[1],
                audioCtx = values[2],
                audioState = values[3],
                audioHead = values[4],
                audioLayers = values[5],
                textCtx = values[6],
                textState = values[7],
                textHead = values[8],
                textLayers = values[9],
                mels = values[10],
                rawFtype = values[11]
            )
        }

        /** Reads the header of [file], or null when it is missing or too short to hold one. */
        fun read(file: File): WhisperModelHeader? = try {
            if (!file.isFile || file.length() < BYTES) null
            else RandomAccessFile(file, "r").use { raf ->
                val bytes = ByteArray(BYTES)
                raf.readFully(bytes)
                parse(bytes)
            }
        } catch (_: IOException) {
            null
        }

        /**
         * Why [header] does not describe [info], or null when it does. Checks what the header can
         * answer: that it is a whisper model at all, that it is multilingual, its size class and its
         * quantisation.
         */
        fun problemFor(info: WhisperModelInfo, header: WhisperModelHeader?): Problem? = when {
            header == null -> Problem.TRUNCATED
            !header.hasMagic || header.mels <= 0 || header.vocab <= 0 -> Problem.NOT_A_MODEL
            info.multilingual && header.vocab < WhisperModelCatalog.MULTILINGUAL_VOCAB ->
                Problem.NOT_MULTILINGUAL
            header.audioState != info.family.audioState ||
                header.audioLayers != info.family.audioLayers -> Problem.WRONG_SIZE_CLASS
            header.ftype != info.quantization.ftype -> Problem.WRONG_QUANTIZATION
            else -> null
        }
    }
}
