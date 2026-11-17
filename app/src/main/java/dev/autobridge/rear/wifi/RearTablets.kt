package dev.autobridge.rear.wifi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The phone's list of paired rear-screen tablets, newest first. The first one is where the
 * player's rear button sends.
 *
 * The address is only the last one seen: on a hotspot it changes from trip to trip, so the sender
 * looks the tablet up again by [Tablet.id] when the old address no longer answers.
 */
object RearTablets {
    private const val PREFS = "rear_tablets"
    private const val KEY = "tablets"

    data class Tablet(val id: String, val name: String, val token: String, val host: String, val port: Int)

    fun all(context: Context): List<Tablet> =
        decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun active(context: Context): Tablet? = all(context).firstOrNull()

    /** Adds or updates [tablet] and makes it the one the rear button uses. */
    fun save(context: Context, tablet: Tablet) = write(context, listOf(tablet) + all(context).filter { it.id != tablet.id })

    /** Keeps the order, only refreshing where [id] was last seen. */
    fun moved(context: Context, id: String, host: String, port: Int) =
        write(context, all(context).map { if (it.id == id) it.copy(host = host, port = port) else it })

    fun forget(context: Context, id: String) = write(context, all(context).filter { it.id != id })

    private fun write(context: Context, tablets: List<Tablet>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, encode(tablets)).apply()
    }

    fun encode(tablets: List<Tablet>): String = JSONArray().apply {
        tablets.forEach {
            put(
                JSONObject().put("id", it.id).put("name", it.name).put("token", it.token)
                    .put("host", it.host).put("port", it.port)
            )
        }
    }.toString()

    fun decode(text: String?): List<Tablet> = runCatching {
        val array = JSONArray(text ?: return emptyList())
        (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            val id = o.optString("id")
            val token = o.optString("token")
            if (id.isEmpty() || token.isEmpty()) null
            else Tablet(id, o.optString("name"), token, o.optString("host"), o.optInt("port", RearProtocol.PORT))
        }
    }.getOrDefault(emptyList())
}
