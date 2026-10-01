package dev.autobridge.subtitles

import dev.autobridge.subtitles.opusmt.MarianDecoding
import dev.autobridge.subtitles.opusmt.MarianVocabulary
import dev.autobridge.subtitles.opusmt.OpusMtAvailability
import dev.autobridge.subtitles.opusmt.OpusMtCatalog
import dev.autobridge.subtitles.opusmt.OpusMtFile
import dev.autobridge.subtitles.opusmt.OpusMtModelLayout
import dev.autobridge.subtitles.opusmt.SentencePieceModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The parts of the Opus-MT stack that are data, string work or arithmetic, covered without a
 * device, a network, ONNX Runtime or a real `.spm` file.
 */
class OpusMtLogicTest {

    // --- Catalog -------------------------------------------------------------------------------

    @Test
    fun `a known pair is marked known and an offered-but-unmirrored pair is unverified`() {
        val known = OpusMtCatalog.model("en", "de")!!
        assertEquals("opus-mt-en-de", known.id)
        assertEquals(OpusMtAvailability.KNOWN, known.availability)

        // Both are offered languages, but en-pl is not in the widely mirrored set.
        val unverified = OpusMtCatalog.model("en", "pl")!!
        assertEquals(OpusMtAvailability.UNVERIFIED, unverified.availability)
    }

    @Test
    fun `a pair into itself or into an unlisted language has no model`() {
        assertNull(OpusMtCatalog.model("en", "en"))
        assertNull(OpusMtCatalog.model("en", "xx"))
        assertNull(OpusMtCatalog.model("xx", "en"))
    }

    @Test
    fun `a model id round-trips back to the same pair`() {
        val model = OpusMtCatalog.model("fr", "en")!!
        val reparsed = OpusMtCatalog.model(model.id)!!
        assertEquals(model.source, reparsed.source)
        assertEquals(model.target, reparsed.target)
    }

    @Test
    fun `the download url points at the quantized graph in the right repository`() {
        val model = OpusMtCatalog.model("en", "de")!!
        val encoderUrl = OpusMtCatalog.downloadUrl(model, OpusMtFile.ENCODER)
        assertTrue(encoderUrl.contains("Xenova/opus-mt-en-de"))
        assertTrue(encoderUrl.endsWith("encoder_model_quantized.onnx"))
        assertEquals("vocab.json", OpusMtCatalog.remotePath(OpusMtFile.VOCABULARY))
    }

    @Test
    fun `models from a source list the known pairs before the unverified ones`() {
        val fromEnglish = OpusMtCatalog.modelsFrom("en")
        val firstUnverified = fromEnglish.indexOfFirst { it.availability == OpusMtAvailability.UNVERIFIED }
        val lastKnown = fromEnglish.indexOfLast { it.availability == OpusMtAvailability.KNOWN }
        assertTrue("known pairs come first", lastKnown < firstUnverified)
    }

    // --- Model layout --------------------------------------------------------------------------

    @Test
    fun `a directory is complete only once every file is present`() {
        val all = OpusMtFile.all.map { it.fileName }
        assertTrue(OpusMtModelLayout.isComplete(all))
        assertFalse(OpusMtModelLayout.isComplete(all.dropLast(1)))
        assertEquals(listOf(OpusMtFile.VOCABULARY), OpusMtModelLayout.missing(all.dropLast(1)))
    }

    @Test
    fun `a cleanup removes partials always and keeps only the requested ids`() {
        val installed = listOf("opus-mt-en-de", "opus-mt-fr-en", "opus-mt-es-en.partial")
        val plan = OpusMtModelLayout.cleanupPlan(installed, keep = setOf("opus-mt-en-de"))
        assertEquals(listOf("opus-mt-es-en.partial", "opus-mt-fr-en"), plan)
    }

    @Test
    fun `size formats in whole megabytes with the small cases spelled out`() {
        assertEquals("0 MB", OpusMtModelLayout.formatSize(0))
        assertEquals("under 1 MB", OpusMtModelLayout.formatSize(512))
        assertEquals("90 MB", OpusMtModelLayout.formatSize(90L * 1024 * 1024))
    }

    // --- SentencePiece -------------------------------------------------------------------------

    @Test
    fun `normalize marks word boundaries and prefixes a boundary`() {
        assertEquals("▁hello▁world", SentencePieceModel.normalize("  hello   world  "))
        assertEquals("", SentencePieceModel.normalize("   "))
    }

    @Test
    fun `detokenize is the inverse of the boundary marking`() {
        assertEquals("hello world", SentencePieceModel.detokenize(listOf("▁hello", "▁world")))
        assertEquals("unbroken", SentencePieceModel.detokenize(listOf("▁un", "bro", "ken")))
    }

    @Test
    fun `encode picks the segmentation the piece scores prefer`() {
        // "▁hello" scores better whole than split, so Viterbi should keep it whole; a character
        // the model has never seen still comes through as its own piece rather than cutting the
        // line short.
        val model = sentencePiece(
            "▁hello" to 0f,
            "▁he" to -5f,
            "llo" to -5f,
            "▁world" to 0f
        )
        assertEquals(listOf("▁hello", "▁world"), model.encode("hello world"))

        val withUnknown = model.encode("hello ♥")
        assertEquals("▁hello", withUnknown.first())
        assertTrue("the unknown glyph survives as its own piece", withUnknown.any { it.contains("♥") })
    }

    // --- Vocabulary ----------------------------------------------------------------------------

    @Test
    fun `encode appends the end token and never a start token`() {
        val vocab = vocabulary()
        val ids = vocab.encode(listOf("▁hi"))
        assertEquals(2, ids.size)
        assertEquals(vocab.idOf("▁hi"), ids[0])
        assertEquals(vocab.endId, ids[1])
    }

    @Test
    fun `decode stops at the end token and drops special tokens`() {
        val vocab = vocabulary()
        val hi = vocab.idOf("▁hi")
        val there = vocab.idOf("▁there")
        val decoded = vocab.decode(listOf(hi, there, vocab.endId, hi))
        assertEquals("hi there", decoded)
    }

    @Test
    fun `an unknown piece maps to the unknown id and a missing id decodes to nothing`() {
        val vocab = vocabulary()
        assertEquals(vocab.unknownId, vocab.idOf("not-in-table"))
        assertEquals("", vocab.pieceOf(9999))
    }

    // --- Marian decoding -----------------------------------------------------------------------

    @Test
    fun `the output allowance grows with input but never past the ceiling`() {
        assertEquals(10, MarianDecoding.maxOutputTokens(1))
        assertEquals(MarianDecoding.MAX_OUTPUT_TOKENS, MarianDecoding.maxOutputTokens(1000))
    }

    @Test
    fun `next token takes the argmax and skips the banned ones`() {
        val logits = floatArrayOf(0.1f, 0.9f, 0.5f)
        assertEquals(1, MarianDecoding.nextToken(logits))
        assertEquals(2, MarianDecoding.nextToken(logits, banned = setOf(1)))
    }

    @Test
    fun `a repeated tail is a loop but a varied one is not`() {
        assertTrue(MarianDecoding.isLooping(listOf(5, 6, 7, 8, 5, 6, 7, 8)))
        assertFalse(MarianDecoding.isLooping(listOf(5, 6, 7, 8, 9, 10, 11, 12)))
        assertFalse(MarianDecoding.isLooping(listOf(5, 6)))
    }

    @Test
    fun `last-position logits are the final row of the flattened cube`() {
        // sequence length 2, vocabulary 3; the last row is the second triple.
        val flat = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)
        val last = MarianDecoding.lastPositionLogits(flat, sequenceLength = 2, vocabularySize = 3)
        assertEquals(listOf(4f, 5f, 6f), last.toList())
    }

    // --- Fixtures ------------------------------------------------------------------------------

    /** A `.spm` model built in memory from pieces, so the parser is exercised by the test too. */
    private fun sentencePiece(vararg pieces: Pair<String, Float>): SentencePieceModel =
        SentencePieceModel.parse(encodeModelProto(pieces.toList()))

    private fun vocabulary(): MarianVocabulary {
        val json = """
            {
              "<pad>": 0,
              "</s>": 1,
              "<unk>": 2,
              "▁hi": 3,
              "▁there": 4
            }
        """.trimIndent()
        return MarianVocabulary.parse(json)
    }

    /**
     * Writes just enough of a `ModelProto` for [SentencePieceModel.parse]: a repeated field 1,
     * each entry a `SentencePiece` with text (field 1), score (fixed32 field 2) and the normal
     * type (field 3).
     */
    private fun encodeModelProto(pieces: List<Pair<String, Float>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((text, score) in pieces) {
            val piece = ByteArrayOutputStream()
            // field 1, wire type 2 (length-delimited): the piece text
            piece.write((1 shl 3) or 2)
            val textBytes = text.toByteArray(Charsets.UTF_8)
            writeVarint(piece, textBytes.size.toLong())
            piece.write(textBytes)
            // field 2, wire type 5 (fixed32): the score, little-endian
            piece.write((2 shl 3) or 5)
            val bits = score.toRawBits()
            for (i in 0 until 4) piece.write((bits ushr (8 * i)) and 0xFF)
            // field 3, wire type 0 (varint): type = NORMAL
            piece.write((3 shl 3) or 0)
            writeVarint(piece, SentencePieceModel.TYPE_NORMAL.toLong())

            // field 1 of ModelProto, wire type 2: this SentencePiece
            out.write((1 shl 3) or 2)
            val pieceBytes = piece.toByteArray()
            writeVarint(out, pieceBytes.size.toLong())
            out.write(pieceBytes)
        }
        return out.toByteArray()
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            val byte = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(byte)
                return
            }
            out.write(byte or 0x80)
        }
    }
}
