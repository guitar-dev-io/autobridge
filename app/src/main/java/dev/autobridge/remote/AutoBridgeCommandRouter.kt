package dev.autobridge.remote

import android.content.Context
import dev.autobridge.R
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.core.state.RecentActivityStore
import dev.autobridge.media.MediaPlaybackClient
import dev.autobridge.mirror.MirrorCoordinator

/**
 * The ONE place a command becomes an action. It never re-implements Browser/Mirror/Media logic; it
 * validates, gates through [FeaturePolicy], resolves the target, and calls the SAME controllers and
 * navigation the existing UI uses (mirroring [dev.autobridge.agent.AgentCommandRouter]).
 *
 * Every command produces exactly one terminal [CommandResult]. Platform/feature denials return
 * FAILED with a reason — the router never bypasses a platform safety restriction.
 *
 * Must be invoked on the main thread (car controllers and MediaController require it); the caller
 * ([RemoteRuntime]) collects the bus on the main dispatcher.
 */
object AutoBridgeCommandRouter {

    fun execute(
        context: Context,
        command: AutoBridgeCommand,
        media: MediaPlaybackClient?
    ): CommandResult {
        AutoBridgeCommandBus.emitProgress(command.id, context.getString(R.string.cmd_progress))
        val result = runCatching { dispatch(context, command, media) }
            .getOrElse { error ->
                CommandResult.failure(
                    command.id,
                    context.getString(
                        R.string.cmd_error,
                        error.message ?: error.javaClass.simpleName
                    ),
                    CommandFailureReason.ERROR
                )
            }
        AutoBridgeCommandBus.emitResult(result)
        if (RemoteSettingsStore.current.rememberHistory) {
            CommandHistoryStore.record(context, command, result)
        }
        // Reflect the result on the car surface as a non-blocking confirmation.
        CarScreenController.host()?.showFeedback(
            if (result.isSuccess) context.getString(R.string.cmd_feedback_success, result.message)
            else context.getString(R.string.cmd_feedback_failure, result.message)
        )
        return result
    }

    private fun dispatch(
        context: Context,
        command: AutoBridgeCommand,
        media: MediaPlaybackClient?
    ): CommandResult {
        val host = CarScreenController.host()
        val id = command.id

        // Commands that require the car app (navigation / browser / mirror surface). Media is
        // available even without a car host because it goes through the shared MediaSession.
        val needsCarHost = command.type !in MEDIA_TYPES
        if (needsCarHost && host == null) {
            return notConnected(context, id)
        }

        return when (command.type) {
            CommandType.OPEN_BROWSER -> {
                if (!FeaturePolicy.app.isAvailable(Feature.BROWSER)) return denied(id, Feature.BROWSER)
                host!!.pushBrowser()
                AutoBridgeStateRepository.setCurrentScreen(RemoteScreen.BROWSER)
                CommandResult.success(id, context.getString(R.string.cmd_browser_opened))
            }

            CommandType.OPEN_URL, CommandType.SEARCH_WEB -> {
                if (!FeaturePolicy.app.isAvailable(Feature.BROWSER)) return denied(id, Feature.BROWSER)
                val url = when (command.type) {
                    CommandType.SEARCH_WEB -> CommandParser.normalizeUrl(command.payload.orEmpty())
                    else -> command.payload?.let { CommandParser.normalizeUrl(it) }
                }
                if (url.isNullOrBlank()) {
                    return CommandResult.failure(
                        id,
                        context.getString(R.string.cmd_invalid_url),
                        CommandFailureReason.INVALID_ARGUMENT
                    )
                }
                val browser = CarScreenController.requireBrowser() ?: return notConnected(context, id)
                browser.openUrl(url)
                RecentActivityStore.record(
                    context,
                    RecentActivityStore.Entry(RecentActivityStore.Kind.BROWSER, hostOf(url), data = url)
                )
                AutoBridgeStateRepository.setBrowserActive(url, null)
                CommandResult.success(id, context.getString(R.string.cmd_opened_host, hostOf(url)))
            }

            CommandType.GO_BACK -> withBrowser(context, id) { it.goBack(); R.string.cmd_went_back }
            CommandType.GO_FORWARD -> withBrowser(context, id) { it.goForward(); R.string.cmd_went_forward }
            CommandType.RELOAD -> withBrowser(context, id) { it.reload(); R.string.cmd_reloaded }

            CommandType.ENTER_FULLSCREEN ->
                withBrowser(context, id) { it.setFullscreen(true); R.string.cmd_fullscreen_on }
            CommandType.EXIT_FULLSCREEN ->
                withBrowser(context, id) { it.setFullscreen(false); R.string.cmd_fullscreen_off }
            CommandType.ENABLE_DESKTOP_MODE ->
                withBrowser(context, id) { it.setDesktopMode(true); R.string.cmd_desktop_on }
            CommandType.DISABLE_DESKTOP_MODE ->
                withBrowser(context, id) { it.setDesktopMode(false); R.string.cmd_desktop_off }

            CommandType.OPEN_MIRROR -> {
                if (!FeaturePolicy.app.isAvailable(Feature.MIRROR)) return denied(id, Feature.MIRROR)
                host!!.pushMirror()
                AutoBridgeStateRepository.setMirrorStatus(
                    if (MirrorCoordinator.isMirroring) MirrorStatus.ACTIVE else MirrorStatus.READY
                )
                CommandResult.success(id, context.getString(R.string.cmd_mirror_opened))
            }

            CommandType.START_MIRROR -> {
                // Starting projection needs MediaProjection consent captured on the phone; the
                // remote cannot fabricate it. Open the mirror surface so the user can grant it.
                if (!FeaturePolicy.app.isAvailable(Feature.MIRROR)) return denied(id, Feature.MIRROR)
                host!!.pushMirror()
                if (MirrorCoordinator.isMirroring) {
                    CommandResult.success(id, context.getString(R.string.cmd_mirror_running))
                } else {
                    CommandResult.success(id, context.getString(R.string.cmd_mirror_needs_consent))
                }
            }

            CommandType.STOP_MIRROR -> {
                dev.autobridge.mirror.ProjectionService.stop(context)
                AutoBridgeStateRepository.setMirrorStatus(MirrorStatus.INACTIVE)
                CommandResult.success(id, context.getString(R.string.cmd_mirror_stopped))
            }

            CommandType.OPEN_MEDIA -> {
                if (!FeaturePolicy.app.isAvailable(Feature.MEDIA)) return denied(id, Feature.MEDIA)
                host!!.pushMedia()
                AutoBridgeStateRepository.setCurrentScreen(RemoteScreen.MEDIA)
                CommandResult.success(id, context.getString(R.string.cmd_media_opened))
            }

            CommandType.PLAY_VIDEO -> {
                if (!FeaturePolicy.app.isAvailable(Feature.VIDEO)) return denied(id, Feature.VIDEO)
                val url = command.payload?.trim()
                if (url.isNullOrBlank()) {
                    return CommandResult.failure(
                        id,
                        context.getString(R.string.cmd_no_video_link),
                        CommandFailureReason.INVALID_ARGUMENT
                    )
                }
                val title = command.extras["title"]?.takeIf { it.isNotBlank() } ?: "Video"
                host!!.pushVideo(url, title)
                RecentActivityStore.record(
                    context,
                    RecentActivityStore.Entry(RecentActivityStore.Kind.MEDIA, title, data = url)
                )
                AutoBridgeStateRepository.setCurrentScreen(RemoteScreen.MEDIA)
                CommandResult.success(id, context.getString(R.string.cmd_video_sent, title))
            }

            CommandType.PLAY -> mediaAction(context, id, media) { it.resume(); R.string.cmd_play }
            CommandType.PAUSE -> mediaAction(context, id, media) { it.pause(); R.string.cmd_pause }
            CommandType.NEXT -> mediaAction(context, id, media) { it.next(); R.string.cmd_next }
            CommandType.PREVIOUS ->
                mediaAction(context, id, media) { it.previous(); R.string.cmd_previous }

            CommandType.OPEN_AGENT -> {
                host!!.pushAgent()
                AutoBridgeStateRepository.setCurrentScreen(RemoteScreen.AGENT)
                RecentActivityStore.record(
                    context,
                    RecentActivityStore.Entry(RecentActivityStore.Kind.AGENT, command.payload ?: "Agent")
                )
                val msg = command.payload?.takeIf { it.isNotBlank() }
                    ?.let { context.getString(R.string.cmd_agent_with_text, it) }
                    ?: context.getString(R.string.cmd_agent_opened)
                CommandResult.success(id, msg)
            }

            CommandType.OPEN_HOME -> {
                host!!.popToHome()
                AutoBridgeStateRepository.setCurrentScreen(RemoteScreen.HOME)
                CommandResult.success(id, context.getString(R.string.cmd_home))
            }

            CommandType.OPEN_SETTINGS -> {
                host!!.pushSettings()
                AutoBridgeStateRepository.setCurrentScreen(RemoteScreen.SETTINGS)
                CommandResult.success(id, context.getString(R.string.cmd_settings_opened))
            }

            CommandType.SEND_TEXT_TO_SCREEN -> {
                val targetName = command.extras["target"] ?: TextInjectionController.Target.BROWSER_SEARCH.name
                val autoSubmit = command.extras["autoSubmit"]?.toBoolean() ?: RemoteSettingsStore.current.autoSubmitText
                val target = runCatching { TextInjectionController.Target.valueOf(targetName) }
                    .getOrDefault(TextInjectionController.Target.BROWSER_SEARCH)
                val outcome =
                    TextInjectionController.send(context, command.payload.orEmpty(), target, autoSubmit)
                if (outcome.success) CommandResult.success(id, outcome.message)
                else CommandResult.failure(id, outcome.message, outcome.reason)
            }

            CommandType.FOCUS_INPUT -> {
                val browser = CarScreenController.activeBrowser
                    ?: return CommandResult.failure(
                        id,
                        context.getString(R.string.cmd_no_browser),
                        CommandFailureReason.PLATFORM_UNAVAILABLE
                    )
                browser.sendTextToSearch("", autoSubmit = false)
                CommandResult.success(id, context.getString(R.string.cmd_input_focused))
            }
        }
    }

    /**
     * [action] returns the string *id* of its confirmation rather than the text, so the message is
     * resolved against [context] here and every caller stays one line long.
     */
    private inline fun withBrowser(
        context: Context,
        id: String,
        action: (CarScreenController.BrowserTarget) -> Int
    ): CommandResult {
        if (!FeaturePolicy.app.isAvailable(Feature.BROWSER)) return denied(id, Feature.BROWSER)
        // Browser display commands act on the live browser, opening one if needed so the request
        // always lands on a real action (same approach as AgentCommandRouter).
        val browser = CarScreenController.requireBrowser() ?: return notConnected(context, id)
        val message = context.getString(action(browser))
        AutoBridgeStateRepository.setBrowserActive(browser.currentUrl, AutoBridgeStateRepository.current.browserTitle)
        return CommandResult.success(id, message)
    }

    /** As [withBrowser]: [action] returns the string id of its confirmation. */
    private inline fun mediaAction(
        context: Context,
        id: String,
        media: MediaPlaybackClient?,
        action: (MediaPlaybackClient) -> Int
    ): CommandResult {
        if (!FeaturePolicy.app.isAvailable(Feature.MEDIA)) return denied(id, Feature.MEDIA)
        if (media == null || !media.isConnected) {
            return CommandResult.failure(
                id,
                context.getString(R.string.cmd_media_unavailable),
                CommandFailureReason.PLATFORM_UNAVAILABLE
            )
        }
        val message = context.getString(action(media))
        AutoBridgeStateRepository.setMedia(media.currentTitle, media.currentArtist, media.isPlaying)
        return CommandResult.success(id, message)
    }

    private fun notConnected(context: Context, id: String): CommandResult =
        CommandResult.failure(
            id,
            context.getString(R.string.cmd_not_connected),
            CommandFailureReason.NOT_CONNECTED
        )

    private fun denied(id: String, feature: Feature): CommandResult =
        CommandResult.failure(id, FeaturePolicy.app.denialMessage(feature), CommandFailureReason.FEATURE_DENIED)

    private fun hostOf(url: String): String =
        runCatching { android.net.Uri.parse(url).host ?: url }.getOrDefault(url)

    private val MEDIA_TYPES = setOf(
        CommandType.PLAY, CommandType.PAUSE, CommandType.NEXT, CommandType.PREVIOUS
    )
}
