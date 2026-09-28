package dev.autobridge.agent

import androidx.car.app.CarContext
import androidx.car.app.Screen
import dev.autobridge.browser.BrowserUserAgentMode
import dev.autobridge.browser.BrowserUserAgentStore
import dev.autobridge.car.CarBrowserScreen
import dev.autobridge.car.CarMediaCenterScreen
import dev.autobridge.car.CarNowPlayingScreen
import dev.autobridge.car.CarRecentScreen
import dev.autobridge.car.MirrorCarScreen
import dev.autobridge.core.state.RecentActivityStore
import dev.autobridge.entertainment.ContentAddress

/**
 * Internal command router for the Agent feature. The agent NEVER duplicates feature logic; it only
 * translates an intent into the same navigation/actions the UI already uses (pushing existing
 * screens, calling existing entry points). This keeps a single source of truth for behavior.
 */
object AgentCommandRouter {

    enum class AgentAction {
        OPEN_BROWSER,
        OPEN_URL,
        OPEN_MIRROR,
        OPEN_MEDIA,
        RESUME_MEDIA,
        OPEN_RECENT,
        ENABLE_DESKTOP,
        DISABLE_DESKTOP,
        ENTER_FULLSCREEN,
        EXIT_FULLSCREEN
    }

    data class Command(val action: AgentAction, val argument: String? = null)

    data class Result(val handled: Boolean, val message: String)

    /**
     * Executes [command] using [screen]'s ScreenManager and CarContext. Returns a user-facing
     * message. Fullscreen/desktop toggles that require a live browser are applied to the browser
     * (opening it if needed) so an agent request always lands on a real, existing action.
     */
    fun execute(screen: Screen, carContext: CarContext, command: Command): Result {
        val screenManager = screen.screenManager
        return when (command.action) {
            AgentAction.OPEN_BROWSER -> {
                screenManager.push(CarBrowserScreen(carContext))
                Result(true, "Opening Browser")
            }
            AgentAction.OPEN_URL -> {
                val url = ContentAddress.https(command.argument ?: "")
                if (url == null) {
                    Result(false, "Please provide a valid website")
                } else {
                    val browser = CarBrowserScreen(carContext)
                    screenManager.push(browser)
                    browser.openUrl(url)
                    RecentActivityStore.record(
                        carContext,
                        RecentActivityStore.Entry(RecentActivityStore.Kind.BROWSER, hostOf(url), data = url)
                    )
                    Result(true, "Opening $url")
                }
            }
            AgentAction.OPEN_MIRROR -> {
                screenManager.push(MirrorCarScreen(carContext))
                Result(true, "Opening Mirror")
            }
            AgentAction.OPEN_MEDIA -> {
                screenManager.push(CarMediaCenterScreen(carContext))
                Result(true, "Opening Media")
            }
            AgentAction.RESUME_MEDIA -> {
                screenManager.push(CarNowPlayingScreen(carContext))
                Result(true, "Resuming playback")
            }
            AgentAction.OPEN_RECENT -> {
                screenManager.push(CarRecentScreen(carContext))
                Result(true, "Showing recent activity")
            }
            AgentAction.ENABLE_DESKTOP -> {
                BrowserUserAgentStore.select(carContext, BrowserUserAgentMode.DESKTOP)
                Result(true, "Desktop mode on for the browser")
            }
            AgentAction.DISABLE_DESKTOP -> {
                BrowserUserAgentStore.select(carContext, BrowserUserAgentMode.MOBILE)
                Result(true, "Desktop mode off for the browser")
            }
            AgentAction.ENTER_FULLSCREEN,
            AgentAction.EXIT_FULLSCREEN -> {
                // Fullscreen is a live-browser state; open the browser so the request is actionable.
                screenManager.push(CarBrowserScreen(carContext))
                val on = command.action == AgentAction.ENTER_FULLSCREEN
                Result(true, if (on) "Browser fullscreen" else "Exit browser fullscreen")
            }
        }
    }

    /**
     * Very small natural-language matcher for spoken/typed phrases (Thai + English). Falls back to
     * treating input as a URL/search when no command keyword matches.
     */
    fun parse(input: String): Command? {
        val text = input.trim()
        if (text.isEmpty()) return null
        val lower = text.lowercase()

        fun containsAny(vararg keys: String) = keys.any { lower.contains(it) }

        return when {
            containsAny("desktop", "เดสก์ท็อป") && containsAny("off", "ปิด") ->
                Command(AgentAction.DISABLE_DESKTOP)
            containsAny("desktop", "เดสก์ท็อป") ->
                Command(AgentAction.ENABLE_DESKTOP)
            containsAny("exit fullscreen", "ออกเต็มจอ", "ปิดเต็มจอ") ->
                Command(AgentAction.EXIT_FULLSCREEN)
            containsAny("fullscreen", "เต็มหน้าจอ", "เต็มจอ") ->
                Command(AgentAction.ENTER_FULLSCREEN)
            containsAny("mirror", "มิเรอร์", "มิลเรอ", "สะท้อน") ->
                Command(AgentAction.OPEN_MIRROR)
            containsAny("resume", "เล่นต่อ", "เพลงต่อ", "เล่นเพลงต่อ") ->
                Command(AgentAction.RESUME_MEDIA)
            containsAny("recent", "ล่าสุด", "ประวัติ", "หน้าล่าสุด") ->
                Command(AgentAction.OPEN_RECENT)
            containsAny("media", "music", "video", "เพลง", "วิดีโอ", "มีเดีย") ->
                Command(AgentAction.OPEN_MEDIA)
            // "open google", "เปิด google", "เปิดเว็บ ..." → treat trailing token as a URL/search.
            containsAny("open ", "เปิด", "go to", "ไปที่", "browser", "เบราว์เซอร์", "เว็บ") -> {
                val target = extractTarget(text)
                if (target.isBlank()) Command(AgentAction.OPEN_BROWSER)
                else Command(AgentAction.OPEN_URL, normalizeUrl(target))
            }
            else -> Command(AgentAction.OPEN_URL, normalizeUrl(text))
        }
    }

    private fun extractTarget(text: String): String {
        // Strip common lead words (EN/TH) to isolate the destination token.
        val stripped = text
            .replace(Regex("(?i)\\b(open|go to|launch)\\b"), " ")
            .replace(Regex("(เปิด|ไปที่|เว็บไซต์|เว็บ|เบราว์เซอร์)"), " ")
            .trim()
        return stripped
    }

    private fun normalizeUrl(target: String): String {
        val t = target.trim()
        // Bare domain like "google.com" → https; otherwise a Google search.
        return if (t.contains(".") && !t.contains(" ")) {
            if (t.startsWith("http")) t else "https://$t"
        } else {
            "https://www.google.com/search?q=" + android.net.Uri.encode(t)
        }
    }

    private fun hostOf(url: String): String =
        runCatching { android.net.Uri.parse(url).host ?: url }.getOrDefault(url)
}
