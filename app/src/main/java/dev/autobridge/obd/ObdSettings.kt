package dev.autobridge.obd

import android.content.Context
import androidx.core.content.edit
import dev.autobridge.obd.profile.BuiltInProfiles
import dev.autobridge.obd.profile.VehicleProfile

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

    /**
     * The vehicle profile in use. A value saved by an older version (the Ranger gearbox choice) is
     * carried over; with nothing saved the generic OBD-II profile applies, which reads only what
     * every car answers the same way.
     */
    fun profileId(context: Context): String {
        val p = prefs(context)
        p.getString("profile", null)?.takeIf { BuiltInProfiles.findResolved(it) != null }?.let { return it }
        return ProfileMigration.fromLegacy(p.getString("transmission", null))
    }

    fun profile(context: Context): VehicleProfile =
        BuiltInProfiles.findResolved(profileId(context))
            ?: checkNotNull(BuiltInProfiles.findResolved(BuiltInProfiles.GENERIC_ID))

    fun setProfileId(context: Context, id: String) {
        prefs(context).edit { putString("profile", id) }
        applyProfile(context)
    }

    /** Makes the gauges (names, units, zones) follow the saved profile; call before drawing them. */
    fun applyProfile(context: Context) {
        GaugeCatalog.profile = profile(context)
    }

    /**
     * Connect on its own when the car connects or the gauges screen opens. On unless switched off:
     * it only acts once an adapter has been chosen.
     */
    fun autoConnect(context: Context): Boolean = prefs(context).getBoolean("auto_connect", true)

    /** The protocol digit that worked last time with [address], so the next start can skip the search. */
    fun lastProtocol(context: Context, address: String): Char? =
        prefs(context).getString("protocol_$address", null)?.firstOrNull()

    fun setLastProtocol(context: Context, address: String, digit: Char) =
        prefs(context).edit { putString("protocol_$address", digit.toString()) }

    fun setAutoConnect(context: Context, value: Boolean) =
        prefs(context).edit { putBoolean("auto_connect", value) }

    /** Warn on the car screen when the car stores a new fault code. On unless switched off. */
    fun dtcAlerts(context: Context): Boolean = prefs(context).getBoolean("dtc_alerts", true)

    fun setDtcAlerts(context: Context, value: Boolean) =
        prefs(context).edit { putBoolean("dtc_alerts", value) }

    /** The stored codes the last answered check found, so only a new one is announced. */
    fun lastStoredDtcs(context: Context): Set<String> =
        prefs(context).getStringSet("dtc_last_stored", null)?.toSet() ?: emptySet()

    fun setLastStoredDtcs(context: Context, codes: Set<String>) =
        prefs(context).edit { putStringSet("dtc_last_stored", codes) }

    /** True for Fahrenheit and miles per hour instead of Celsius and km/h. */
    fun imperial(context: Context): Boolean = prefs(context).getBoolean("imperial", false)

    fun setImperial(context: Context, value: Boolean) =
        prefs(context).edit { putBoolean("imperial", value) }

    /** The pages the car shows, arranged on the phone. */
    fun pages(context: Context): List<GaugePage> =
        GaugePages.decode(prefs(context).getString("pages", null))

    fun setPages(context: Context, pages: List<GaugePage>) =
        prefs(context).edit { putString("pages", GaugePages.encode(pages)) }

    /** How the gauges are drawn (dial, tile, colours, text size). */
    fun style(context: Context): GaugeStyle = GaugeStyle.decode(prefs(context).getString("style", null))

    fun setStyle(context: Context, style: GaugeStyle) =
        prefs(context).edit { putString("style", style.encode()) }
}
