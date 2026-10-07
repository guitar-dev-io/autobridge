package dev.autobridge.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WhisperModelCatalogTest {

    private val required = listOf(
        "tiny-q5_1", "tiny-q8_0", "tiny",
        "base-q4_k", "base-q5_k", "base-q5_1", "base-q6_k", "base-q8_0", "base",
        "small-q4_k", "small-q5_1", "small-q8_0"
    )

    @Test fun `every required model is listed once`() {
        val ids = WhisperModelCatalog.models.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertEquals(required.toSet(), ids.toSet())
    }

    @Test fun `no english-only model is offered`() {
        WhisperModelCatalog.models.forEach {
            assertTrue(it.id, it.multilingual)
            assertFalse(it.id, it.id.contains(".en"))
        }
    }

    @Test fun `file names and urls follow whisper cpp's naming`() {
        WhisperModelCatalog.models.forEach { info ->
            assertEquals("ggml-${info.id}.bin", info.fileName)
            info.downloadUrl?.let { assertEquals("${WhisperModelCatalog.HUB}/ggml-${info.id}.bin", it) }
            assertTrue(info.sizeBytes > 0)
        }
    }

    @Test fun `k-quants are import only, everything else downloads`() {
        WhisperModelCatalog.models.forEach { info ->
            val kQuant = info.quantization.label.endsWith("_K")
            assertEquals(info.id, !kQuant, info.downloadable)
        }
    }

    @Test fun `recommended and low-end defaults`() {
        assertEquals("base-q8_0", WhisperModelCatalog.recommended.id)
        assertEquals(WhisperModelTier.RECOMMENDED, WhisperModelCatalog.recommended.tier)
        assertEquals(WhisperModelTier.LOW_END, WhisperModelCatalog.find(WhisperModelCatalog.LOW_END_ID)!!.tier)
        assertEquals(
            listOf("base-q8_0", "tiny-q8_0", "base-q5_1", "small-q5_1"),
            WhisperModelCatalog.suggested.map { it.id }
        )
        assertTrue(WhisperModelCatalog.models.single { it.tier == WhisperModelTier.RECOMMENDED }.downloadable)
    }

    @Test fun `display sizes match what the hub publishes`() {
        assertEquals("81.8 MB", VoiceFormat.size(WhisperModelCatalog.find("base-q8_0")!!.sizeBytes))
        assertEquals("43.5 MB", VoiceFormat.size(WhisperModelCatalog.find("tiny-q8_0")!!.sizeBytes))
        assertEquals("59.7 MB", VoiceFormat.size(WhisperModelCatalog.find("base-q5_1")!!.sizeBytes))
        assertEquals("190.1 MB", VoiceFormat.size(WhisperModelCatalog.find("small-q5_1")!!.sizeBytes))
    }

    // --- header ----------------------------------------------------------------------------------

    private fun header(
        magic: Int = WhisperModelHeader.MAGIC,
        vocab: Int = 51865,
        state: Int = 512,
        layers: Int = 6,
        ftype: Int = 2007
    ): ByteArray = ByteBuffer.allocate(WhisperModelHeader.BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(magic); putInt(vocab); putInt(1500); putInt(state); putInt(8); putInt(layers)
        putInt(448); putInt(state); putInt(8); putInt(layers); putInt(80); putInt(ftype)
    }.array()

    @Test fun `header of base q8_0 matches base q8_0 and nothing else`() {
        val parsed = WhisperModelHeader.parse(header())!!
        assertEquals(7, parsed.ftype)
        assertNull(WhisperModelHeader.problemFor(WhisperModelCatalog.find("base-q8_0")!!, parsed))
        assertEquals("base-q8_0", WhisperModelCatalog.match(parsed)?.id)
        assertEquals(
            WhisperModelHeader.Problem.WRONG_QUANTIZATION,
            WhisperModelHeader.problemFor(WhisperModelCatalog.find("base-q5_1")!!, parsed)
        )
        assertEquals(
            WhisperModelHeader.Problem.WRONG_SIZE_CLASS,
            WhisperModelHeader.problemFor(WhisperModelCatalog.find("tiny-q8_0")!!, parsed)
        )
    }

    @Test fun `full precision ftype has no version factor`() {
        assertEquals("base", WhisperModelCatalog.match(WhisperModelHeader.parse(header(ftype = 1))!!)?.id)
        // There is no full-precision small in the list, so one is not recognised as anything.
        assertNull(WhisperModelCatalog.match(WhisperModelHeader.parse(header(state = 768, layers = 12, ftype = 1))!!))
    }

    @Test fun `k-quant headers are recognised`() {
        assertEquals("base-q6_k", WhisperModelCatalog.match(WhisperModelHeader.parse(header(ftype = 2014))!!)?.id)
        assertEquals("small-q4_k", WhisperModelCatalog.match(WhisperModelHeader.parse(header(state = 768, layers = 12, ftype = 2012))!!)?.id)
    }

    @Test fun `bad magic, english-only and truncated headers are refused`() {
        val base = WhisperModelCatalog.find("base-q8_0")!!
        assertEquals(WhisperModelHeader.Problem.NOT_A_MODEL, WhisperModelHeader.problemFor(base, WhisperModelHeader.parse(header(magic = 0x3c68746d))))
        assertEquals(WhisperModelHeader.Problem.NOT_MULTILINGUAL, WhisperModelHeader.problemFor(base, WhisperModelHeader.parse(header(vocab = 51864))))
        assertNull(WhisperModelHeader.parse(ByteArray(20)))
        assertEquals(WhisperModelHeader.Problem.TRUNCATED, WhisperModelHeader.problemFor(base, null))
        assertNotNull(WhisperModelHeader.parse(header()))
    }
}
