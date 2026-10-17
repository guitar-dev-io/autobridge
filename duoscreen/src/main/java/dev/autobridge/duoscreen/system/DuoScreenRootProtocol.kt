package dev.autobridge.duoscreen.system

import java.util.Base64

/**
 * The line protocol between [DuoScreenRootOps] (this app) and [DuoScreenRootServer] (the root
 * helper), over the helper's stdin and stdout.
 *
 * Text, one request per line and one reply per line, because a `su` pipe is all there is between
 * the two: no binder can be handed across it. Nothing a request carries needs one either — package
 * and component names are plain strings, and an input event is its Parcel bytes, which hold no
 * binder or file descriptor. Replies start with [REPLY_PREFIX] so anything else a ROM's `su` or
 * `app_process` prints on stdout is told apart and skipped.
 *
 * Pure, so the encoding is tested on the JVM.
 */
internal object DuoScreenRootProtocol {
    const val REPLY_PREFIX = "@"

    /** The helper's first line, once it is running as root and ready for requests. */
    const val READY = "READY"

    sealed interface Command {
        data object Ping : Command
        data class Launch(
            val displayId: Int,
            val packageName: String,
            val component: String?,
            val allowSecondInstance: Boolean,
        ) : Command
        data class Tasks(val displayId: Int) : Command
        data class Ime(val displayId: Int) : Command
        class Motion(val displayId: Int, val parcel: ByteArray) : Command
        class Key(val displayId: Int, val parcel: ByteArray) : Command
    }

    fun encode(command: Command): String = when (command) {
        Command.Ping -> "PING"
        is Command.Launch -> listOf(
            "LAUNCH",
            command.displayId,
            if (command.allowSecondInstance) 1 else 0,
            command.packageName,
            command.component ?: NONE,
        ).joinToString(" ")
        is Command.Tasks -> "TASKS ${command.displayId}"
        is Command.Ime -> "IME ${command.displayId}"
        is Command.Motion -> "MOTION ${command.displayId} ${base64(command.parcel)}"
        is Command.Key -> "KEY ${command.displayId} ${base64(command.parcel)}"
    }

    /** Null for anything malformed, which the helper answers as a failure rather than dying on. */
    fun decode(line: String): Command? = runCatching {
        val parts = line.trim().split(' ')
        when (parts[0]) {
            "PING" -> Command.Ping
            "LAUNCH" -> {
                require(parts.size == 5)
                Command.Launch(
                    displayId = parts[1].toInt(),
                    packageName = parts[3].also { require(it.isNotEmpty()) },
                    component = parts[4].takeUnless { it == NONE },
                    allowSecondInstance = parts[2] == "1",
                )
            }
            "TASKS" -> Command.Tasks(parts[1].toInt())
            "IME" -> Command.Ime(parts[1].toInt())
            "MOTION" -> Command.Motion(parts[1].toInt(), Base64.getDecoder().decode(parts[2]))
            "KEY" -> Command.Key(parts[1].toInt(), Base64.getDecoder().decode(parts[2]))
            else -> null
        }
    }.getOrNull()

    /** A result as a reply line: 1, 0, or ? for "cannot tell". */
    fun reply(value: Boolean?): String = REPLY_PREFIX + when (value) {
        true -> "1"
        false -> "0"
        null -> "?"
    }

    /** The body of a reply line, or null when [line] is not a reply. */
    fun replyBody(line: String): String? =
        line.takeIf { it.startsWith(REPLY_PREFIX) }?.substring(REPLY_PREFIX.length)

    fun parseResult(body: String?): Boolean? = when (body) {
        "1" -> true
        "0" -> false
        else -> null
    }

    private const val NONE = "-"

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
