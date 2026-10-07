package dev.autobridge.fuel

import android.content.Context
import dev.autobridge.R
import java.text.NumberFormat

/**
 * Saves a fill-up or charge that was spoken ("เติม 40 ลิตร 1,400 บาท") to the fuel log: today's
 * date, the car's odometer when it has just reported one, the last grade used. The same entry as
 * typing it on the phone, so a mis-heard figure is fixed there (tap the row > Edit).
 */
object FuelVoiceLogger {
    /** [saved] is true when an entry was written; [message] is what to show and say either way. */
    data class Outcome(val saved: Boolean, val message: String)

    /** A car odometer reading older than this is not taken to be "now". */
    private const val ODOMETER_FRESH_MS = 6 * 60 * 60 * 1000L

    fun log(context: Context, spoken: String, nowMs: Long = System.currentTimeMillis()): Outcome {
        val voice = FuelVoiceParser.parse(spoken)
            ?: return Outcome(false, context.getString(R.string.voice_fuel_not_understood))
        // The log is read in litres or in kWh according to the vehicle; do not mix the two.
        if (voice.electric != FuelLogStore.isEv(context)) {
            return Outcome(false, context.getString(if (voice.electric) R.string.voice_fuel_vehicle_is_fuel else R.string.voice_fuel_vehicle_is_ev))
        }
        val odometer = CarVehicleData.lastOdometer(context)?.takeIf { nowMs - it.second < ODOMETER_FRESH_MS }?.first
        val grade = if (voice.electric) "" else FuelLogStore.lastFuelType(context)
        FuelLogStore.add(context, voice.amount, voice.baht, odometer, "", grade, nowMs)
        val number = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }
        val row = context.getString(
            if (voice.electric) R.string.fuel_row_title_ev else R.string.fuel_row_title,
            number.format(voice.amount), number.format(voice.baht)
        )
        return Outcome(true, context.getString(R.string.voice_fuel_saved, row))
    }
}
