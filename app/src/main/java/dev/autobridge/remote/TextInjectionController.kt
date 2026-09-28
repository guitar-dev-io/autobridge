package dev.autobridge.remote

/**
 * Abstraction for delivering text to a target on the car screen (spec §10).
 *
 * Order of preference for the browser (per the spec):
 *   1. Native input into a focused WebView field (handled by the browser target, which uses the
 *      WebView's own APIs — no JS injection unless strictly necessary and only on a WebView
 *      AutoBridge fully controls).
 *   2. Fallback to the browser's search/address input.
 *
 * This controller does NOT itself touch a WebView; it delegates to the live [CarScreenController]
 * browser target so there is a single implementation of text entry. Agent text goes through the
 * command bus as an [CommandType.OPEN_AGENT] command.
 */
object TextInjectionController {

    enum class Target { FOCUSED_INPUT, BROWSER_SEARCH, AGENT }

    /**
     * Sends [text] to [target]. [autoSubmit] presses Enter afterwards when supported (spec §11).
     * Returns a [CommandResult]-friendly outcome.
     */
    fun send(text: String, target: Target, autoSubmit: Boolean): Outcome {
        val clean = sanitize(text)
        if (clean.isEmpty()) return Outcome(false, "ไม่มีข้อความให้ส่ง", CommandFailureReason.INVALID_ARGUMENT)

        return when (target) {
            Target.AGENT -> {
                // Route to the Agent via the bus using the raw text.
                AutoBridgeCommandBus.send(
                    AutoBridgeCommand(type = CommandType.OPEN_AGENT, payload = clean, source = CommandSource.MOBILE)
                )
                Outcome(true, "ส่งให้ Agent แล้ว")
            }
            Target.FOCUSED_INPUT, Target.BROWSER_SEARCH -> {
                val browser = CarScreenController.activeBrowser
                if (browser == null) {
                    Outcome(false, "ไม่มี Browser ที่กำลังเปิดอยู่", CommandFailureReason.PLATFORM_UNAVAILABLE)
                } else {
                    // The browser target prefers a focused field then falls back to the search box.
                    browser.sendTextToSearch(clean, autoSubmit)
                    Outcome(true, "ส่งข้อความไปยัง Browser แล้ว")
                }
            }
        }
    }

    /** Trims and strips control characters; the WebView layer does its own value escaping. */
    private fun sanitize(text: String): String =
        text.trim().filter { it == '\n' || !it.isISOControl() }

    data class Outcome(
        val success: Boolean,
        val message: String,
        val reason: CommandFailureReason = CommandFailureReason.NONE
    )
}
