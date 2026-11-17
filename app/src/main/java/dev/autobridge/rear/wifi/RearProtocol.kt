package dev.autobridge.rear.wifi

import org.json.JSONObject

/**
 * The words the phone and a rear-screen tablet exchange. See `docs/REAR_SCREEN.md`, phase 2.
 *
 * Plain JSON over HTTP, so either end can be checked with `curl` and nothing beyond Android itself
 * is needed. Kept free of Android types so the shapes can be tested on the JVM.
 */
object RearProtocol {
    /** What the tablet announces on the network (NSD/mDNS). */
    const val SERVICE_TYPE = "_abrear._tcp."
    const val PORT = 47820
    const val TOKEN_HEADER = "X-AutoBridge-Token"

    /** Something to play on the rear screen. */
    data class Play(val url: String, val title: String, val positionMs: Long, val muted: Boolean) {
        fun toJson(): String = JSONObject()
            .put("url", url).put("title", title).put("positionMs", positionMs).put("muted", muted)
            .toString()

        companion object {
            /** Null for a body that is not a play command (no URL, or not JSON at all). */
            fun parse(body: String): Play? = runCatching {
                val json = JSONObject(body)
                val url = json.optString("url").trim()
                if (url.isEmpty()) return null
                Play(
                    url = url,
                    title = json.optString("title"),
                    positionMs = json.optLong("positionMs", 0L).coerceAtLeast(0L),
                    muted = json.optBoolean("muted", false),
                )
            }.getOrNull()
        }
    }

    /** Who the tablet is, before pairing: enough to list it on the phone. */
    data class Info(val id: String, val name: String) {
        fun toJson(): String = JSONObject().put("id", id).put("name", name).toString()

        companion object {
            fun parse(body: String): Info? = runCatching {
                val json = JSONObject(body)
                Info(json.getString("id"), json.optString("name"))
            }.getOrNull()
        }
    }

    /** What the tablet is doing, for the phone's notification. */
    data class Status(val playing: Boolean, val muted: Boolean, val title: String) {
        fun toJson(): String = JSONObject()
            .put("playing", playing).put("muted", muted).put("title", title).toString()

        companion object {
            fun parse(body: String): Status? = runCatching {
                val json = JSONObject(body)
                Status(json.optBoolean("playing"), json.optBoolean("muted"), json.optString("title"))
            }.getOrNull()
        }
    }

    fun pairBody(code: String): String = JSONObject().put("code", code).toString()

    fun pairCode(body: String): String? = runCatching { JSONObject(body).optString("code").trim() }
        .getOrNull()?.takeIf { it.isNotEmpty() }

    fun tokenBody(token: String): String = JSONObject().put("token", token).toString()

    fun token(body: String): String? = runCatching { JSONObject(body).optString("token") }
        .getOrNull()?.takeIf { it.isNotEmpty() }
}
