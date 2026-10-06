package dev.autobridge.maintenance

import android.content.Context
import dev.autobridge.R
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.roundToLong

/** The wording shared by the phone list and the car screen. */
object MaintenanceText {
    /** The names offered to a new vehicle, by [MaintenancePreset.key]. */
    fun presetName(key: String): Int = when (key) {
        "engine_oil" -> R.string.maint_engine_oil
        "tires" -> R.string.maint_tires
        "air_filter" -> R.string.maint_air_filter
        "cabin_filter" -> R.string.maint_cabin_filter
        "brake_fluid" -> R.string.maint_brake_fluid
        "battery_coolant" -> R.string.maint_battery_coolant
        "battery_12v" -> R.string.maint_battery_12v
        else -> R.string.maint_road_tax
    }

    /** "In 3,200 km · In 40 days": where [status] stands, each interval that applies. */
    fun standing(context: Context, status: MaintenanceStatus): String {
        val km = NumberFormat.getIntegerInstance()
        val parts = listOfNotNull(
            status.kmLeft?.let {
                val whole = abs(it).roundToLong()
                if (it < 0) context.getString(R.string.maint_over_km, km.format(whole)) else context.getString(R.string.maint_in_km, km.format(whole))
            },
            status.daysLeft?.let {
                if (it < 0) context.getString(R.string.maint_over_days, -it) else context.getString(R.string.maint_in_days, it)
            },
        )
        return parts.ifEmpty { listOf(context.getString(R.string.maint_odometer_unknown)) }.joinToString(" · ")
    }

    /** "Every 10,000 km · every 12 months". */
    fun interval(context: Context, item: MaintenanceItem): String = listOfNotNull(
        item.intervalKm.takeIf { it > 0 }?.let { context.getString(R.string.maint_every_km, NumberFormat.getIntegerInstance().format(it)) },
        item.intervalMonths.takeIf { it > 0 }?.let { context.getString(R.string.maint_every_months, it) },
    ).joinToString(" · ")

    fun badge(state: DueState): String = when (state) {
        DueState.OVERDUE -> "🔴"
        DueState.SOON -> "🟡"
        DueState.OK -> "🟢"
    }
}
