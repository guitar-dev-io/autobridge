package dev.autobridge.browser

import dev.autobridge.remote.CommandParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every text box resolves the same words to the same page. Before, the car keyboard searched
 * `weather` while the phone bar, Send to car, the car browser's own loader and the Remote opened
 * `https://weather`, and only some of them searched on the chosen engine.
 */
class InputResolutionConsistencyTest {
    private val inputs = listOf(
        "weather", "bodyslam live", "เพลงใหม่", "youtube.com", "m.youtube.com/watch?v=1",
        "https://example.com/a?b=1", "http://example.com", "localhost", "javascript:alert(1)", "   "
    )

    @Test fun allEntryPointsAgreeForEveryEngine() {
        for (engine in SearchEngine.entries) for (input in inputs) {
            val expected = BrowserInputResolver.resolve(input, engine)
            assertEquals("phone bar / Send to car: '$input'", expected, BrowserInputResolver.resolveBrowserInput(input, engine))
            assertEquals("car keyboard: '$input'", expected, BrowserInputResolver.resolveTyped(input, engine))
            assertEquals("car browser loader: '$input'", expected, BrowserDefaults.resolve(input, engine))
            assertEquals("Remote / Agent: '$input'", expected, CommandParser.normalizeUrl(input, engine))
        }
    }
}
