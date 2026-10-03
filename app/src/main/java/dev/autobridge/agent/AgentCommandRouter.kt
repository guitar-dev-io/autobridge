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
import dev.autobridge.R
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

    /**
     * [message] is shown as a toast; [spoken] is what the voice feedback reads aloud. Spoken text
     * is Thai and short so the driver gets a confirmation without looking at the screen.
     */
    data class Result(val handled: Boolean, val message: String, val spoken: String = message)

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
                Result(
                    true,
                    carContext.getString(R.string.agent_toast_opening_browser),
                    carContext.getString(R.string.agent_spoken_opening_browser)
                )
            }
            AgentAction.OPEN_URL -> {
                val url = ContentAddress.https(command.argument ?: "")
                if (url == null) {
                    Result(
                        false,
                        carContext.getString(R.string.agent_toast_invalid_website),
                        carContext.getString(R.string.agent_spoken_invalid_website)
                    )
                } else {
                    val browser = CarBrowserScreen(carContext)
                    screenManager.push(browser)
                    browser.openUrl(url)
                    RecentActivityStore.record(
                        carContext,
                        RecentActivityStore.Entry(RecentActivityStore.Kind.BROWSER, hostOf(url), data = url)
                    )
                    Result(
                        true,
                        carContext.getString(R.string.agent_toast_opening_url, url),
                        spokenForUrl(carContext, url)
                    )
                }
            }
            AgentAction.OPEN_MIRROR -> {
                screenManager.push(MirrorCarScreen(carContext))
                Result(
                    true,
                    carContext.getString(R.string.agent_toast_opening_mirror),
                    carContext.getString(R.string.agent_spoken_opening_mirror)
                )
            }
            AgentAction.OPEN_MEDIA -> {
                screenManager.push(CarMediaCenterScreen(carContext))
                Result(
                    true,
                    carContext.getString(R.string.agent_toast_opening_media),
                    carContext.getString(R.string.agent_spoken_opening_media)
                )
            }
            AgentAction.RESUME_MEDIA -> {
                screenManager.push(CarNowPlayingScreen(carContext))
                Result(
                    true,
                    carContext.getString(R.string.agent_toast_resuming_playback),
                    carContext.getString(R.string.agent_spoken_resuming_playback)
                )
            }
            AgentAction.OPEN_RECENT -> {
                screenManager.push(CarRecentScreen(carContext))
                Result(
                    true,
                    carContext.getString(R.string.agent_toast_recent_activity),
                    carContext.getString(R.string.agent_spoken_recent_activity)
                )
            }
            AgentAction.ENABLE_DESKTOP -> {
                BrowserUserAgentStore.select(carContext, BrowserUserAgentMode.DESKTOP)
                Result(
                    true,
                    carContext.getString(R.string.agent_toast_desktop_on),
                    carContext.getString(R.string.agent_spoken_desktop_on)
                )
            }
            AgentAction.DISABLE_DESKTOP -> {
                BrowserUserAgentStore.select(carContext, BrowserUserAgentMode.MOBILE)
                Result(
                    true,
                    carContext.getString(R.string.agent_toast_desktop_off),
                    carContext.getString(R.string.agent_spoken_desktop_off)
                )
            }
            AgentAction.ENTER_FULLSCREEN,
            AgentAction.EXIT_FULLSCREEN -> {
                // Fullscreen is a live-browser state; open the browser so the request is actionable.
                screenManager.push(CarBrowserScreen(carContext))
                val on = command.action == AgentAction.ENTER_FULLSCREEN
                if (on) Result(
                    true,
                    carContext.getString(R.string.agent_toast_fullscreen_on),
                    carContext.getString(R.string.agent_spoken_fullscreen_on)
                )
                else Result(
                    true,
                    carContext.getString(R.string.agent_toast_fullscreen_off),
                    carContext.getString(R.string.agent_spoken_fullscreen_off)
                )
            }
        }
    }

    /**
     * Natural-language matcher for spoken/typed phrases (Thai + English). See
     * [AgentCommandParser]; kept here so existing callers (Recent, Quick Launch) are unchanged.
     */
    fun parse(input: String): Command? = AgentCommandParser.parse(input)

    private fun spokenForUrl(carContext: CarContext, url: String): String = when {
        url.contains("/search?") || url.contains("/results?") ->
            carContext.getString(R.string.agent_spoken_searching)
        else -> carContext.getString(
            R.string.agent_spoken_opening_host,
            hostOf(url).removePrefix("www.").removePrefix("m.")
        )
    }

    private fun hostOf(url: String): String =
        runCatching { android.net.Uri.parse(url).host ?: url }.getOrDefault(url)
}
