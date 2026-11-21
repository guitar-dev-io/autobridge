package dev.autobridge.voice

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ParkedModelsTest {

    private lateinit var base: File

    @Before
    fun setUp() {
        base = Files.createTempDirectory("parked-models").toFile()
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    @Test
    fun `nothing to remove when the directory was never made`() {
        assertNull(VoiceRuntime.deleteModelsDirectory(File(base, "whisper/models")))
    }

    @Test
    fun `removes finished, partial and checksum files and reports their size`() {
        val dir = File(base, "whisper/models").apply { mkdirs() }
        File(dir, "ggml-base-q8_0.bin").writeBytes(ByteArray(1_000))
        File(dir, "ggml-tiny-q5_1.bin.part").writeBytes(ByteArray(300))
        File(dir, "ggml-base-q8_0.bin.sha256").writeText("ab")

        assertEquals(1_302L, VoiceRuntime.deleteModelsDirectory(dir))
        assertFalse(dir.exists())
        assertTrue("only the models directory goes", File(base, "whisper").isDirectory)
    }

    @Test
    fun `an empty directory is removed and counts as nothing freed`() {
        val dir = File(base, "whisper/models").apply { mkdirs() }

        assertEquals(0L, VoiceRuntime.deleteModelsDirectory(dir))
        assertFalse(dir.exists())
    }
}
