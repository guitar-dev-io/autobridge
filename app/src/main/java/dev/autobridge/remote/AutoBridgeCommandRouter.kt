package dev.autobridge.remote

import android.content.Context
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
        AutoBridgeCommandBus.emitProgress(command.id, "กำลังทำคำสั่ง…")
        val result = runCatching { dispatch(context, command, media) }
            .getOrElse { error ->
                CommandResult.failure(
                    command.id,
                    "เกิดข้อผิดพลาด: ${error.message ?: error.javaClass.simpleName}",
                    CommandFailureReason.ERROR
                )
            }
        AutoBridgeCommandBus.emitResult(result)
        if (RemoteSettingsStore.current.rememberHistory) {
            CommandHistoryStore.record(context, command, result)
        }
        // Reflect the result on the car surface as a non-blocking confirmation.
        CarScreenController.host()?.showFeedback(
            if (result.isSuccess) "✓ ${result.message}\nจากคำสั่งบนมือถือ" else "✕ ${result.message}"
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
            return CommandResult.failure(id, "Android Auto not connected", CommandFailureReason.NOT_CONNECTED)
        }

        return when (command.type) {
            CommandType.OPEN_BROWSER -> {
                if (!FeaturePolicy.app.isAvailable(Feature.BROWSER)) return denied(id, Feature.BROWSER)
                host!!.pushBrowser()
                AutoBridgeStateRepository.setCurrentScreen(RemoteScreen.BROWSER)
                CommandResult.success(id, "เปิด Browser แล้ว")
            }

            CommandType.OPEN_URL, CommandType.SEARCH_WEB -> {
                if (!FeaturePolicy.app.isAvailable(Feature.BROWSER)) return denied(id, Feature.BROWSER)
                val url = when (command.type) {
                    CommandType.SEARCH_WEB -> CommandParser.normalizeUrl(command.payload.orEmpty())
                    else -> command.payload?.let { CommandParser.normalizeUrl(it) }
                }
                if (url.isNullOrBlank()) {
                    return CommandResult.failure(id, "URL ไม่ถูกต้อง", CommandFailureReason.INVALID_ARGUMENT)
                }
                val browser = CarScreenController.requireBrowser()
                    ?: return CommandResult.failure(id, "Android Auto not connected", CommandFailureReason.NOT_CONNECTED)
                browser.openUrl(url)
                RecentActivityStore.record(
                    context,
                    RecentActivityStore.Entry(RecentActivityStore.Kind.BROWSER, hostOf(url), data = url)
                )
                AutoBridgeStateRepository.setBrowserActive(url, null)
                CommandResult.success(id, "เปิด ${hostOf(url)} แล้ว")
            }

            CommandType.GO_BACK -> withBrowser(id) { it.goBack(); "กลับหน้าก่อน" }
            CommandType.GO_FORWARD -> withBrowser(id) { it.goForward(); "ไปหน้าถัดไป" }
            CommandType.RELOAD -> withBrowser(id) { it.reload(); "รีเฟรชแล้ว" }

            CommandType.ENTER_FULLSCREEN -> withBrowser(id) { it.setFullscreen(true); "เต็มหน้าจอ" }
            CommandType.EXIT_FULLSCREEN -> withBrowser(id) { it.setFullscreen(false); "ออกจากเต็มหน้าจอ" }
            CommandType.ENABLE_DESKTOP_MODE -> withBrowser(id) { it.setDesktopMode(true); "Desktop mode เปิด" }
            CommandType.DISABLE_DESKTOP_MODE -> withBrowser(id) { it.setDesktopMode(false); "Desktop mode ปิด" }

            CommandType.OPEN_MIRROR -> {
                if (!FeaturePolicy.app.isAvailable(Feature.MIRROR)) return denied(id, Feature.MIRROR)
                host!!.pushMirror()
                AutoBridgeStateRepository.setMirrorStatus(
                    if (MirrorCoordinator.isMirroring) MirrorStatus.ACTIVE else MirrorStatus.READY
                )
                CommandResult.success(id, "เปิด Mirror แล้ว")
            }

            CommandType.START_MIRROR -> {
                // Starting projection needs MediaProjection consent captured on the phone; the
                // remote cannot fabricate it. Open the mirror surface so the user can grant it.
                if (!FeaturePolicy.app.isAvailable(Feature.MIRROR)) return denied(id, Feature.MIRROR)
                host!!.pushMirror()
                if (MirrorCoordinator.isMirroring) {
                    CommandResult.success(id, "Mirror กำลังทำงาน")
                } else {
                    CommandResult.success(id, "เปิดหน้า Mirror — เริ่มการสะท้อนจากมือถือเพื่ออนุญาต")
                }
            }

            CommandType.STOP_MIRROR -> {
                dev.autobridge.mirror.ProjectionService.stop(context)
                AutoBridgeStateRepository.setMirrorStatus(MirrorStatus.INACTIVE)
                CommandResult.success(id, "หยุด Mirror แล้ว")
            }

            CommandType.OPEN_MEDIA -> {
                if (!FeaturePolicy.app.isAvailable(Feature.MEDIA)) return denied(id, Feature.MEDIA)
                host!!.pushMedia()
                AutoBridgeStateRepository.setCurrentScreen(RemoteScreen.MEDIA)
                CommandResult.success(id, "เปิด Media แล้ว")
            }

            CommandType.PLAY -> mediaAction(id, media) { it.resume(); "เล่นเพลง" }
            CommandType.PAUSE -> mediaAction(id, media) { it.pause(); "หยุดเพลง" }
            CommandType.NEXT -> mediaAction(id, media) { it.next(); "เพลงถัดไป" }
            CommandType.PREVIOUS -> mediaAction(id, media) { it.previous(); "เพลงก่อนหน้า" }

            CommandType.OPEN_AGENT -> {
                host!!.pushAgent()
                AutoBridgeStateRepository.setCurrentScreen(RemoteScreen.AGENT)
                RecentActivityStore.record(
                    context,
                    RecentActivityStore.Entry(RecentActivityStore.Kind.AGENT, command.payload ?: "Agent")
                )
                val msg = command.payload?.takeIf { it.isNotBlank() }?.let { "Agent: $it" } ?: "เปิด Agent แล้ว"
                CommandResult.success(id, msg)
            }

            CommandType.OPEN_HOME -> {
                host!!.popToHome()
                AutoBridgeStateRepository.setCurrentScreen(RemoteScreen.HOME)
                CommandResult.success(id, "หน้าหลัก")
            }

            CommandType.OPEN_SETTINGS -> {
                host!!.pushSettings()
                AutoBridgeStateRepository.setCurrentScreen(RemoteScreen.SETTINGS)
                CommandResult.success(id, "เปิดตั้งค่าแล้ว")
            }

            CommandType.SEND_TEXT_TO_SCREEN -> {
                val targetName = command.extras["target"] ?: TextInjectionController.Target.BROWSER_SEARCH.name
                val autoSubmit = command.extras["autoSubmit"]?.toBoolean() ?: RemoteSettingsStore.current.autoSubmitText
                val target = runCatching { TextInjectionController.Target.valueOf(targetName) }
                    .getOrDefault(TextInjectionController.Target.BROWSER_SEARCH)
                val outcome = TextInjectionController.send(command.payload.orEmpty(), target, autoSubmit)
                if (outcome.success) CommandResult.success(id, outcome.message)
                else CommandResult.failure(id, outcome.message, outcome.reason)
            }

            CommandType.FOCUS_INPUT -> {
                val browser = CarScreenController.activeBrowser
                    ?: return CommandResult.failure(id, "ไม่มี Browser ที่กำลังเปิดอยู่", CommandFailureReason.PLATFORM_UNAVAILABLE)
                browser.sendTextToSearch("", autoSubmit = false)
                CommandResult.success(id, "เปิดช่องพิมพ์แล้ว")
            }
        }
    }

    private inline fun withBrowser(
        id: String,
        action: (CarScreenController.BrowserTarget) -> String
    ): CommandResult {
        if (!FeaturePolicy.app.isAvailable(Feature.BROWSER)) return denied(id, Feature.BROWSER)
        // Browser display commands act on the live browser, opening one if needed so the request
        // always lands on a real action (same approach as AgentCommandRouter).
        val browser = CarScreenController.requireBrowser()
            ?: return CommandResult.failure(id, "Android Auto not connected", CommandFailureReason.NOT_CONNECTED)
        val message = action(browser)
        AutoBridgeStateRepository.setBrowserActive(browser.currentUrl, AutoBridgeStateRepository.current.browserTitle)
        return CommandResult.success(id, message)
    }

    private inline fun mediaAction(
        id: String,
        media: MediaPlaybackClient?,
        action: (MediaPlaybackClient) -> String
    ): CommandResult {
        if (!FeaturePolicy.app.isAvailable(Feature.MEDIA)) return denied(id, Feature.MEDIA)
        if (media == null || !media.isConnected) {
            return CommandResult.failure(id, "Media ยังไม่พร้อม", CommandFailureReason.PLATFORM_UNAVAILABLE)
        }
        val message = action(media)
        AutoBridgeStateRepository.setMedia(media.currentTitle, media.currentArtist, media.isPlaying)
        return CommandResult.success(id, message)
    }

    private fun denied(id: String, feature: Feature): CommandResult =
        CommandResult.failure(id, FeaturePolicy.app.denialMessage(feature), CommandFailureReason.FEATURE_DENIED)

    private fun hostOf(url: String): String =
        runCatching { android.net.Uri.parse(url).host ?: url }.getOrDefault(url)

    private val MEDIA_TYPES = setOf(
        CommandType.PLAY, CommandType.PAUSE, CommandType.NEXT, CommandType.PREVIOUS
    )
}
