package dev.autobridge.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decoding, cue cleanup and config arithmetic of the subtitle stack, covered without a
 * Context, a player or a track.
 */
class SubtitleSettingsCodecTest {

    @Test
    fun `a missing or renamed engine falls back to ML Kit`() {
        assertEquals(TranslationEngine.ML_KIT, SubtitleSettingsCodec.engine(null))
        assertEquals(TranslationEngine.ML_KIT, SubtitleSettingsCodec.engine("GOOGLE_CLOUD"))
        assertEquals(TranslationEngine.OPUS_MT, SubtitleSettingsCodec.engine("OPUS_MT"))
    }

    @Test
    fun `a stored language is honoured only while it is still offered`() {
        assertEquals("de", SubtitleSettingsCodec.language("de", "en"))
        assertEquals("en", SubtitleSettingsCodec.language("xx", "en"))
        assertEquals("en", SubtitleSettingsCodec.language(null, "en"))
    }

    @Test
    fun `the device language is used only when the screen can show it`() {
        assertEquals("fr", SubtitleSettingsCodec.deviceLanguage("fr"))
        assertEquals("en", SubtitleSettingsCodec.deviceLanguage("xx"))
        assertEquals("en", SubtitleSettingsCodec.deviceLanguage(null))
    }

    @Test
    fun `a config is active only when enabled and the pair differs`() {
        val base = SubtitleTranslationConfig(
            enabled = true,
            engine = TranslationEngine.ML_KIT,
            sourceLanguage = "en",
            targetLanguage = "de",
            showOriginal = false
        )
        assertTrue(base.isActive)
        assertFalse(base.copy(enabled = false).isActive)
        assertFalse(base.copy(targetLanguage = "EN", sourceLanguage = "en").isActive)
        assertTrue(base.copy(targetLanguage = "EN", sourceLanguage = "en").isSamePair)
    }

    @Test
    fun `the signature changes with engine and pair so caches and models invalidate`() {
        val a = SubtitleTranslationConfig(true, TranslationEngine.ML_KIT, "en", "de", false)
        val b = a.copy(engine = TranslationEngine.OPUS_MT)
        val c = a.copy(targetLanguage = "fr")
        assertEquals("ML_KIT:en>de", a.signature)
        assertFalse(a.signature == b.signature)
        assertFalse(a.signature == c.signature)
    }

    @Test
    fun `cue text strips markup and joins display line breaks into one`() {
        assertEquals(
            "Hello there, friend",
            SubtitleCueText.normalize("<i>Hello</i> there,\n{\\an8}friend")
        )
        assertEquals("one two", SubtitleCueText.normalizeAll(listOf("one", null, "", "two")))
    }

    @Test
    fun `a line of only punctuation or music notes is not worth translating`() {
        assertTrue(SubtitleCueText.isUntranslatable("- ♪♪ -"))
        assertFalse(SubtitleCueText.isUntranslatable("Hola"))
    }

    @Test
    fun `the display text leads with the translation and only shows the original when asked`() {
        val translated = SubtitleLine(original = "Hallo", translated = "Hello", showOriginal = false)
        assertEquals("Hello", translated.displayText)

        val both = translated.copy(showOriginal = true)
        assertEquals("Hello\nHallo", both.displayText)

        // Before an engine answers, the original is what is on screen.
        assertEquals("Hallo", SubtitleLine(original = "Hallo").displayText)
        // A translation identical to the source is not shown twice.
        assertEquals("Hi", SubtitleLine("Hi", "Hi", showOriginal = true).displayText)
    }
}
