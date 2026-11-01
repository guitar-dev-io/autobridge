package dev.autobridge.obd

import android.content.Context
import androidx.core.content.edit

/** The adapter the user picked, and the vehicle choices the gauges depend on. */
object ObdSettings {
    private const val FILE = "obd_gauges"

    data class Adapter(val address: String, val name: String)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun adapter(context: Context): Adapter? {
        val p = prefs(context)
        val address = p.getString("address", null) ?: return null
        return Adapter(address, p.getString("name", null) ?: address)
    }

    fun setAdapter(context: Context, adapter: Adapter?) = prefs(context).edit {
        if (adapter == null) remove("address").remove("name")
        else putString("address", adapter.address).putString("name", adapter.name)
    }

    fun transmission(context: Context): Transmission =
        runCatching { Transmission.valueOf(prefs(context).getString("transmission", null) ?: "") }
            .getOrDefault(Transmission.T5)

    fun setTransmission(context: Context, value: Transmission) {
        prefs(context).edit { putString("transmission", value.name) }
        GaugeSpec.profile = value
    }

    /** Makes the zones follow the saved vehicle; call before drawing gauges. */
    fun applyProfile(context: Context) {
        GaugeSpec.profile = transmission(context)
    }

    /** Reconnect on its own the next time the gauges screen opens. */
    fun autoConnect(context: Context): Boolean = prefs(context).getBoolean("auto_connect", false)

    fun setAutoConnect(context: Context, value: Boolean) =
        prefs(context).edit { putBoolean("auto_connect", value) }

    /** True for Fahrenheit and miles per hour instead of Celsius and km/h. */
    fun imperial(context: Context): Boolean = prefs(context).getBoolean("imperial", false)

    fun setImperial(context: Context, value: Boolean) =
        prefs(context).edit { putBoolean("imperial", value) }

    /** The pages the car shows, arranged on the phone. */
    fun pages(context: Context): List<GaugePage> =
        GaugePages.decode(prefs(context).getString("pages", null))

    fun setPages(context: Context, pages: List<GaugePage>) =
        prefs(context).edit { putString("pages", GaugePages.encode(pages)) }
}
