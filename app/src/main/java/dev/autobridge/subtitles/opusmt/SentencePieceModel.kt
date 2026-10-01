package dev.autobridge.subtitles.opusmt

import java.text.Normalizer

/**
 * The SentencePiece unigram model shipped beside an Opus-MT export (`source.spm`, `target.spm`),
 * and the tokenizer that runs on it.
 *
 * Why this is hand-written rather than a library call: SentencePiece's own implementation is C++,
 * and the JVM bindings for it mean another native library in the APK next to ONNX Runtime. What is
 * actually needed from the `.spm` file is the piece list with its log probabilities, and what is
 * needed from the algorithm is the Viterbi segmentation over that list. Both are small, and both
 * are exactly reproducible, so they are testable here instead of being a black box.
 *
 * The file is a protocol-buffer `ModelProto`. Only field 1 (the repeated piece list) is read; the
 * trainer and normalizer specs that follow it are skipped, because the normalization Marian models
 * use is the default NFKC-plus-whitespace one implemented in [normalize].
 */
class SentencePieceModel private constructor(
    /** The pieces in model order; the index is not the vocabulary id, which `vocab.json` owns. */
    val pieces: List<Piece>
) {
    data class Piece(val text: String, val score: Float, val type: Int) {
        /** A control or unknown piece is never produced by segmentation, only by the model itself. */
        val isUsable: Boolean get() = type == TYPE_NORMAL || type == TYPE_USER_DEFINED
    }

    private val scores: Map<String, Float> =
        pieces.filter { it.isUsable }.associate { it.text to it.score }

    private val longestPiece: Int = scores.keys.maxOfOrNull { it.length } ?: 1

    /**
     * The penalty for a character no piece covers.
     *
     * SentencePiece would emit `<unk>` here. Segmentation still has to get past the character, so
     * it is emitted as its own piece and left for the vocabulary to map to the unknown id; the
     * penalty makes sure any real piece covering the same span wins instead.
     */
    private val unknownScore: Float = (scores.values.minOrNull() ?: 0f) - 10f

    /**
     * [text] as the pieces a Marian encoder expects, lowest-cost segmentation first.
     *
     * Returns an empty list for text that normalizes to nothing, which is the right answer for a
     * cue that was only markup.
     */
    fun encode(text: String): List<String> {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return emptyList()

        val length = normalized.length
        // best[i] is the score of the best segmentation of the first i characters; back[i] is the
        // length of the piece that ends there, which is all backtracking needs.
        val best = FloatArray(length + 1) { Float.NEGATIVE_INFINITY }
        val back = IntArray(length + 1)
        best[0] = 0f

        for (start in 0 until length) {
            if (best[start] == Float.NEGATIVE_INFINITY) continue
            val limit = minOf(longestPiece, length - start)
            for (span in 1..limit) {
                val score = scores[normalized.substring(start, start + span)] ?: continue
                val candidate = best[start] + score
                if (candidate > best[start + span]) {
                    best[start + span] = candidate
                    back[start + span] = span
                }
            }
            // The fallback is always available, so a character outside the model cannot cut the
            // chain and lose the rest of the line. One whole code point at a time: half a
            // surrogate pair is not a character any vocabulary has.
            val fallbackSpan = Character.charCount(normalized.codePointAt(start))
            val fallback = best[start] + unknownScore
            if (fallback > best[start + fallbackSpan]) {
                best[start + fallbackSpan] = fallback
                back[start + fallbackSpan] = fallbackSpan
            }
        }

        val result = ArrayDeque<String>()
        var cursor = length
        while (cursor > 0) {
            val span = back[cursor].coerceAtLeast(1)
            result.addFirst(normalized.substring(cursor - span, cursor))
            cursor -= span
        }
        return result.toList()
    }

    companion object {
        /** `ModelProto.SentencePiece.Type` values; only these two are segmentation candidates. */
        const val TYPE_NORMAL = 1
        const val TYPE_USER_DEFINED = 4

        /** SentencePiece's word-boundary marker, U+2581 LOWER ONE EIGHTH BLOCK. */
        const val WORD_START = '▁'

        /** `ModelProto.pieces`. */
        private const val FIELD_PIECES = 1

        /** `SentencePiece.piece`, `.score`, `.type`. */
        private const val FIELD_PIECE_TEXT = 1
        private const val FIELD_PIECE_SCORE = 2
        private const val FIELD_PIECE_TYPE = 3

        /**
         * The text as SentencePiece sees it: NFKC-normalized, runs of whitespace collapsed, each
         * space replaced by the word-boundary marker, and a marker added at the front.
         *
         * The leading marker is `add_dummy_prefix`, on in every Marian model. Without it the first
         * word of every line is segmented as a mid-word fragment and the translation starts wrong.
         */
        fun normalize(text: String): String {
            val collapsed = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace(Regex("\\s+"), " ")
                .trim()
            if (collapsed.isEmpty()) return ""
            return WORD_START + collapsed.replace(' ', WORD_START)
        }

        /** Pieces back into text: the inverse of [normalize] for everything but the NFKC step. */
        fun detokenize(pieces: List<String>): String =
            pieces.joinToString("").replace(WORD_START, ' ').trim()

        /**
         * Reads a `.spm` file.
         *
         * Unknown fields are skipped by wire type rather than rejected, so an export carrying a
         * newer `ModelProto` field still loads.
         */
        fun parse(bytes: ByteArray): SentencePieceModel {
            val reader = ProtoReader(bytes)
            val pieces = mutableListOf<Piece>()
            while (reader.hasMore()) {
                val tag = reader.readTag() ?: break
                if (tag.field == FIELD_PIECES && tag.wireType == ProtoReader.WIRE_LENGTH) {
                    pieces.add(parsePiece(reader.readBytes()))
                } else {
                    reader.skip(tag.wireType)
                }
            }
            require(pieces.isNotEmpty()) { "SentencePiece model carries no pieces" }
            return SentencePieceModel(pieces)
        }

        private fun parsePiece(bytes: ByteArray): Piece {
            val reader = ProtoReader(bytes)
            var text = ""
            var score = 0f
            var type = TYPE_NORMAL
            while (reader.hasMore()) {
                val tag = reader.readTag() ?: break
                when {
                    tag.field == FIELD_PIECE_TEXT && tag.wireType == ProtoReader.WIRE_LENGTH ->
                        text = reader.readBytes().toString(Charsets.UTF_8)
                    tag.field == FIELD_PIECE_SCORE && tag.wireType == ProtoReader.WIRE_FIXED32 ->
                        score = reader.readFloat()
                    tag.field == FIELD_PIECE_TYPE && tag.wireType == ProtoReader.WIRE_VARINT ->
                        type = reader.readVarint().toInt()
                    else -> reader.skip(tag.wireType)
                }
            }
            return Piece(text, score, type)
        }
    }
}

/**
 * Just enough protocol-buffer wire format to read a `ModelProto`: varints, length-delimited
 * fields, fixed32, and the ability to skip anything else by wire type.
 */
internal class ProtoReader(private val bytes: ByteArray) {
    private var offset = 0

    data class Tag(val field: Int, val wireType: Int)

    fun hasMore(): Boolean = offset < bytes.size

    fun readTag(): Tag? {
        if (!hasMore()) return null
        val key = readVarint()
        return Tag(field = (key ushr 3).toInt(), wireType = (key and 7L).toInt())
    }

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            require(offset < bytes.size) { "truncated varint" }
            val byte = bytes[offset++].toInt()
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
        throw IllegalArgumentException("varint longer than 64 bits")
    }

    fun readBytes(): ByteArray {
        val length = readVarint().toInt()
        require(length >= 0 && offset + length <= bytes.size) { "truncated length-delimited field" }
        val slice = bytes.copyOfRange(offset, offset + length)
        offset += length
        return slice
    }

    fun readFloat(): Float {
        require(offset + 4 <= bytes.size) { "truncated fixed32" }
        var raw = 0
        // Little-endian, per the wire format.
        for (i in 0 until 4) raw = raw or ((bytes[offset + i].toInt() and 0xFF) shl (8 * i))
        offset += 4
        return Float.fromBits(raw)
    }

    fun skip(wireType: Int) {
        when (wireType) {
            WIRE_VARINT -> readVarint()
            WIRE_FIXED64 -> offset += 8
            WIRE_LENGTH -> readBytes()
            WIRE_FIXED32 -> offset += 4
            else -> throw IllegalArgumentException("unsupported wire type $wireType")
        }
        require(offset <= bytes.size) { "truncated field" }
    }

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LENGTH = 2
        const val WIRE_FIXED32 = 5
    }
}
