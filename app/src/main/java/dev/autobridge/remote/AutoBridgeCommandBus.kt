package dev.autobridge.remote

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-global, thread-safe command bus that connects command *producers* (Mobile Remote, Quick
 * Commands, Agent, Android Auto) with the single *consumer* ([AutoBridgeCommandRouter]) and streams
 * acknowledgements back to any observer.
 *
 * Design goals from the spec:
 *  - thread safe (SharedFlow is safe for concurrent emit/collect)
 *  - lifecycle safe (SharedFlow survives Activity/Screen recreation because it is a singleton)
 *  - reconnect safe (results replay the most recent so a re-subscribing UI is not left blank)
 *  - duplicate protection (recent command ids are remembered; a re-sent id is dropped)
 *
 * The router subscribes exactly once via [dev.autobridge.remote.RemoteRuntime]; UIs observe
 * [results] and (via the repository) state.
 */
object AutoBridgeCommandBus {

    private val _commands = MutableSharedFlow<AutoBridgeCommand>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val commands: SharedFlow<AutoBridgeCommand> = _commands.asSharedFlow()

    private val _results = MutableSharedFlow<CommandResult>(
        replay = 1,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val results: SharedFlow<CommandResult> = _results.asSharedFlow()

    private val lock = Any()
    private val recentIds = ArrayDeque<String>()
    private val recentIdSet = HashSet<String>()

    private const val RECENT_ID_LIMIT = 64

    /**
     * Submit a command for execution. Returns false if the command id was seen recently (duplicate
     * from a double-tap, reconnect, re-subscribe, or configuration change) and was therefore
     * ignored. The caller may treat false as "already handled".
     */
    fun send(command: AutoBridgeCommand): Boolean {
        synchronized(lock) {
            if (recentIdSet.contains(command.id)) return false
            recentIds.addLast(command.id)
            recentIdSet.add(command.id)
            while (recentIds.size > RECENT_ID_LIMIT) {
                val evicted = recentIds.removeFirst()
                recentIdSet.remove(evicted)
            }
        }
        return _commands.tryEmit(command)
    }

    /** Emits an in-progress status update (EXECUTING) for observers that show live progress. */
    fun emitProgress(commandId: String, message: String) {
        _results.tryEmit(CommandResult(commandId, CommandStatus.EXECUTING, message))
    }

    /** Emits the terminal acknowledgement for a command. */
    fun emitResult(result: CommandResult) {
        _results.tryEmit(result)
    }
}
