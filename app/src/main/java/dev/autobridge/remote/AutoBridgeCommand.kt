package dev.autobridge.remote

import java.util.UUID

/**
 * Central command vocabulary shared by the Mobile Remote, Android Auto, the Agent, and the system.
 *
 * A command is a *declarative intent* — it never contains feature logic. [AutoBridgeCommandRouter]
 * is the only place that turns a command into an action, and it does so by calling the SAME
 * controllers/navigation the existing UI uses. This keeps a single source of truth for behavior
 * (see [dev.autobridge.agent.AgentCommandRouter] for the original pattern this extends).
 */
enum class CommandType {
    // Browser
    OPEN_BROWSER,
    OPEN_URL,
    SEARCH_WEB,
    GO_BACK,
    GO_FORWARD,
    RELOAD,

    // Mirror
    OPEN_MIRROR,
    START_MIRROR,
    STOP_MIRROR,

    // Media
    OPEN_MEDIA,
    PLAY,
    PAUSE,
    NEXT,
    PREVIOUS,

    // Agent
    OPEN_AGENT,

    // Display
    ENTER_FULLSCREEN,
    EXIT_FULLSCREEN,
    ENABLE_DESKTOP_MODE,
    DISABLE_DESKTOP_MODE,

    // Navigation
    OPEN_HOME,
    OPEN_SETTINGS,

    // Text
    SEND_TEXT_TO_SCREEN,
    FOCUS_INPUT
}

/** Where a command originated. Used for dedup, feedback routing, and history labelling. */
enum class CommandSource { MOBILE, ANDROID_AUTO, AGENT, SYSTEM }

/** Lifecycle of a command from creation to terminal state. */
enum class CommandStatus { PENDING, EXECUTING, SUCCESS, FAILED }

/**
 * A single command instance.
 *
 * @param id unique id used for duplicate protection and acknowledgement correlation.
 * @param type the declarative intent.
 * @param payload optional argument (URL, search query, text to inject, target field, etc.).
 * @param source where it came from.
 * @param timestamp creation time (epoch millis).
 * @param status current lifecycle status (immutable copies are emitted as it progresses).
 */
data class AutoBridgeCommand(
    val id: String = UUID.randomUUID().toString(),
    val type: CommandType,
    val payload: String? = null,
    val source: CommandSource = CommandSource.MOBILE,
    val timestamp: Long = System.currentTimeMillis(),
    val status: CommandStatus = CommandStatus.PENDING,
    /** Optional structured extras (e.g. text-injection target, auto-submit flag). */
    val extras: Map<String, String> = emptyMap()
)

/** Reason codes for a failed command so the UI can render an appropriate message. */
enum class CommandFailureReason {
    NONE,
    NOT_CONNECTED,
    PLATFORM_UNAVAILABLE,
    FEATURE_DENIED,
    INVALID_ARGUMENT,
    NO_HANDLER,
    ERROR
}

/**
 * Acknowledgement for a command. Every command that reaches the router produces exactly one
 * terminal [CommandResult] (SUCCESS or FAILED).
 */
data class CommandResult(
    val commandId: String,
    val status: CommandStatus,
    val message: String,
    val reason: CommandFailureReason = CommandFailureReason.NONE
) {
    val isSuccess: Boolean get() = status == CommandStatus.SUCCESS

    companion object {
        fun success(commandId: String, message: String) =
            CommandResult(commandId, CommandStatus.SUCCESS, message)

        fun failure(
            commandId: String,
            message: String,
            reason: CommandFailureReason = CommandFailureReason.ERROR
        ) = CommandResult(commandId, CommandStatus.FAILED, message, reason)
    }
}
