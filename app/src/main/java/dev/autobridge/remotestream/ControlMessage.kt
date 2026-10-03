package dev.autobridge.remotestream

import org.json.JSONObject

/**
 * The commands AutoBridge sends back to a stream host, and their wire format.
 *
 * The host is rendering the content, so everything the driver does on the car screen has to
 * travel back to it. The format is the structured one from the spec:
 *
 * ```json
 * { "type": "playback", "action": "seek", "positionMs": 120000 }
 * ```
 *
 * ## Why these commands and not touch events
 *
 * Forwarding raw screen touches would be simpler to write and much worse to use. A touch is
 * meaningless without the host's exact layout and scale, it breaks the moment the host window
 * moves or the car's surface is a different size, and it gives the driver a phone-sized hit
 * target at the wheel. Named intents survive all of that: the host decides what "select" means
 * for whatever it is showing. Touch forwarding is therefore not implemented, per the spec's
 * "unless absolutely necessary".
 *
 * Encoding and decoding are pure, so the contract with the host is covered by unit tests on both
 * directions of the wire.
 */
sealed interface ControlMessage {

    val type: String
    fun toJson(): JSONObject

    /** play / pause / seek / stop — anything about the content's timeline. */
    data class Playback(val action: Action, val positionMs: Long? = null) : ControlMessage {
        enum class Action { PLAY, PAUSE, SEEK, STOP }

        override val type = "playback"
        override fun toJson(): JSONObject = JSONObject()
            .put("type", type)
            .put("action", action.name.lowercase())
            .apply { positionMs?.let { put("positionMs", it) } }
    }

    /** Text typed on the phone, delivered to whatever the host has focused. */
    data class Keyboard(val text: String, val submit: Boolean = false) : ControlMessage {
        override val type = "keyboard"
        override fun toJson(): JSONObject = JSONObject()
            .put("type", type)
            .put("action", "text")
            .put("text", text)
            .put("submit", submit)
    }

    /** Directional movement and selection — the car's rotary/D-pad vocabulary. */
    data class Navigation(val action: Action) : ControlMessage {
        enum class Action { UP, DOWN, LEFT, RIGHT, SELECT, BACK }

        override val type = "navigation"
        override fun toJson(): JSONObject = JSONObject()
            .put("type", type)
            .put("action", action.name.lowercase())
    }

    /**
     * A scroll, in content pixels rather than gesture units, so the host does not have to know
     * anything about the car's touch panel to apply it.
     */
    data class Scroll(val deltaX: Int, val deltaY: Int) : ControlMessage {
        override val type = "scroll"
        override fun toJson(): JSONObject = JSONObject()
            .put("type", type)
            .put("action", "by")
            .put("deltaX", deltaX)
            .put("deltaY", deltaY)
    }

    /** Asks the host to render [url]. The one command that starts a session rather than steering it. */
    data class Open(val url: String, val positionMs: Long = 0L) : ControlMessage {
        override val type = "open"
        override fun toJson(): JSONObject = JSONObject()
            .put("type", type)
            .put("action", "url")
            .put("url", url)
            .put("positionMs", positionMs)
    }

    fun encode(): String = toJson().toString()

    companion object {
        /**
         * Reads a message back, for tests and for a host that echoes commands.
         *
         * Unknown types and malformed JSON return null rather than throwing: a host is free to
         * extend the protocol, and an unrecognised message must not take the connection down.
         */
        fun decode(raw: String?): ControlMessage? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val json = JSONObject(raw)
                val action = json.optString("action").uppercase()
                when (json.optString("type")) {
                    "playback" -> Playback(
                        action = enumValueOrNull<Playback.Action>(action) ?: return null,
                        positionMs = if (json.has("positionMs")) json.optLong("positionMs") else null
                    )
                    "keyboard" -> Keyboard(json.optString("text"), json.optBoolean("submit"))
                    "navigation" -> Navigation(enumValueOrNull<Navigation.Action>(action) ?: return null)
                    "scroll" -> Scroll(json.optInt("deltaX"), json.optInt("deltaY"))
                    "open" -> Open(json.optString("url"), json.optLong("positionMs"))
                    else -> null
                }
            }.getOrNull()
        }

        private inline fun <reified T : Enum<T>> enumValueOrNull(name: String): T? =
            enumValues<T>().firstOrNull { it.name == name }
    }
}

/**
 * Status the host pushes to AutoBridge, as opposed to the commands going the other way.
 *
 * Kept separate from [ControlMessage] because the directions are not symmetric: the host reports
 * what it is rendering, it never asks the car to do anything.
 */
data class HostStatusMessage(
    val state: String,
    val title: String?,
    val positionMs: Long,
    val durationMs: Long,
    val error: String?
) {
    companion object {
        fun decode(raw: String?): HostStatusMessage? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val json = JSONObject(raw)
                if (json.optString("type") != "status") return null
                HostStatusMessage(
                    state = json.optString("state"),
                    title = json.optString("title").takeIf { it.isNotBlank() },
                    positionMs = json.optLong("positionMs"),
                    durationMs = json.optLong("durationMs"),
                    error = json.optString("error").takeIf { it.isNotBlank() }
                )
            }.getOrNull()
        }
    }
}
