package dev.autobridge.subtitles

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sequencing [SubtitleTranslationPipeline] is responsible for, driven against a fake engine so
 * the ordering rules are asserted without ONNX Runtime, ML Kit or a real clock.
 *
 * The pipeline launches translation on an injected scope; the test backs that scope with an
 * [UnconfinedTestDispatcher], so a launch runs up to its first real suspension point eagerly and a
 * gated translation only completes when the test releases it. That is what lets "a late result for
 * a replaced line is dropped" be a deterministic assertion rather than a race.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubtitleTranslationPipelineTest {

    private val activeConfig = SubtitleTranslationConfig(
        enabled = true,
        engine = TranslationEngine.OPUS_MT,
        sourceLanguage = "de",
        targetLanguage = "en",
        showOriginal = false
    )

    /** A translator the test steers: each line's result is a deferred it can complete on demand. */
    private class FakeTranslator(
        override val signature: String = "OPUS_MT:de>en"
    ) : SubtitleTranslator {
        override val engine = TranslationEngine.OPUS_MT

        val requested = mutableListOf<String>()
        private val gates = ArrayDeque<CompletableDeferred<String>>()
        var closed = false
            private set

        /** Hands back a gate for the next translate() call, to complete when the test wants it. */
        fun gateNext(): CompletableDeferred<String> =
            CompletableDeferred<String>().also { gates.addLast(it) }

        override suspend fun prepare() = Unit

        override suspend fun translate(text: String): String {
            requested.add(text)
            val gate = gates.removeFirstOrNull() ?: return "[$text]"
            return gate.await()
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `the original goes up first and is replaced in place when the translation lands`() = runTest(UnconfinedTestDispatcher()) {
        val published = mutableListOf<SubtitleLine>()
        val engine = FakeTranslator()
        val gate = engine.gateNext()
        val pipeline = SubtitleTranslationPipeline(
            scope = backgroundScope,
            publish = { published.add(it) }
        )
        pipeline.bind(engine, activeConfig)

        pipeline.onText("Hallo Welt")
        // The original is on screen immediately, with no translation yet.
        assertEquals("Hallo Welt", published.last().original)
        assertNull(published.last().translated)

        gate.complete("Hello world")
        assertEquals("Hello world", published.last().translated)
        assertEquals("Hallo Welt", published.last().original)
    }

    @Test
    fun `a repeat of the line already on screen is not re-translated`() = runTest(UnconfinedTestDispatcher()) {
        val engine = FakeTranslator()
        val pipeline = SubtitleTranslationPipeline(scope = backgroundScope, publish = {})
        pipeline.bind(engine, activeConfig)

        pipeline.onText("Guten Tag")
        pipeline.onText("Guten Tag")
        assertEquals(listOf("Guten Tag"), engine.requested)
    }

    @Test
    fun `a cached pair comes back with no engine call`() = runTest(UnconfinedTestDispatcher()) {
        val cache = SubtitleLineCache()
        cache.put("OPUS_MT:de>en", "Danke", "Thanks")
        val engine = FakeTranslator()
        val published = mutableListOf<SubtitleLine>()
        val pipeline = SubtitleTranslationPipeline(
            scope = backgroundScope,
            cache = cache,
            publish = { published.add(it) }
        )
        pipeline.bind(engine, activeConfig)

        pipeline.onText("Danke")
        assertEquals("Thanks", published.last().translated)
        assertTrue("a cache hit never reaches the engine", engine.requested.isEmpty())
    }

    @Test
    fun `a late result for a line that was replaced is dropped`() = runTest(UnconfinedTestDispatcher()) {
        val published = mutableListOf<SubtitleLine>()
        val engine = FakeTranslator()
        val slow = engine.gateNext()
        val fast = engine.gateNext()
        val pipeline = SubtitleTranslationPipeline(
            scope = backgroundScope,
            publish = { published.add(it) }
        )
        pipeline.bind(engine, activeConfig)

        pipeline.onText("erste Zeile")
        pipeline.onText("zweite Zeile")
        // The second line resolves first; then the first, now stale, resolves.
        fast.complete("second line")
        slow.complete("first line")

        val translations = published.mapNotNull { it.translated }
        assertTrue("the current line's translation shows", translations.contains("second line"))
        assertFalse("the stale line's translation is discarded", translations.contains("first line"))
    }

    @Test
    fun `an empty cue clears the screen`() = runTest(UnconfinedTestDispatcher()) {
        val published = mutableListOf<SubtitleLine>()
        val pipeline = SubtitleTranslationPipeline(
            scope = backgroundScope,
            publish = { published.add(it) }
        )
        pipeline.bind(FakeTranslator(), activeConfig)

        pipeline.onText("Hallo")
        pipeline.onText("")
        assertEquals(SubtitleLine.NONE, published.last())
    }

    @Test
    fun `an untranslatable line is shown as-is without an engine call`() = runTest(UnconfinedTestDispatcher()) {
        val engine = FakeTranslator()
        val published = mutableListOf<SubtitleLine>()
        val pipeline = SubtitleTranslationPipeline(
            scope = backgroundScope,
            publish = { published.add(it) }
        )
        pipeline.bind(engine, activeConfig)

        pipeline.onText("- ♪♪ -")
        assertEquals("- ♪♪ -", published.last().original)
        assertTrue(engine.requested.isEmpty())
    }

    @Test
    fun `binding a new engine closes the old one`() = runTest(UnconfinedTestDispatcher()) {
        val first = FakeTranslator()
        val second = FakeTranslator()
        val pipeline = SubtitleTranslationPipeline(scope = backgroundScope, publish = {})

        pipeline.bind(first, activeConfig)
        pipeline.bind(second, activeConfig.copy(targetLanguage = "fr"))
        assertTrue("the replaced engine is closed", first.closed)
        assertFalse(second.closed)
    }

    @Test
    fun `with translation off the original is shown and the engine is never asked`() = runTest(UnconfinedTestDispatcher()) {
        val engine = FakeTranslator()
        val published = mutableListOf<SubtitleLine>()
        val pipeline = SubtitleTranslationPipeline(
            scope = backgroundScope,
            publish = { published.add(it) }
        )
        pipeline.bind(engine, activeConfig.copy(enabled = false))

        pipeline.onText("Hallo")
        assertEquals("Hallo", published.last().original)
        assertNull(published.last().translated)
        assertTrue(engine.requested.isEmpty())
    }

    @Test
    fun `a failing translation publishes the original with the error`() = runTest(UnconfinedTestDispatcher()) {
        val engine = FakeTranslator()
        val gate = engine.gateNext()
        val published = mutableListOf<SubtitleLine>()
        val pipeline = SubtitleTranslationPipeline(
            scope = backgroundScope,
            publish = { published.add(it) }
        )
        pipeline.bind(engine, activeConfig)

        pipeline.onText("kaputt")
        gate.completeExceptionally(IllegalStateException("engine down"))
        assertEquals("kaputt", published.last().original)
        assertEquals("engine down", published.last().error)
    }
}
