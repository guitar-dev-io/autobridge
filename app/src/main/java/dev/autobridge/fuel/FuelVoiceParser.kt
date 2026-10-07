package dev.autobridge.fuel

/** What a spoken fill-up or charge said: how much energy, what it cost, and whether it was kWh. */
data class FuelVoice(val amount: Double, val baht: Double, val electric: Boolean)

/**
 * Reads "เติม 40 ลิตร 1,400 บาท" / "filled 40 liters for 1400 baht" / "ชาร์จ 30 kWh 200 บาท" out of
 * what a speech recognizer returned. Pure Kotlin so it is tested on the JVM against phrases as a
 * recognizer actually writes them.
 *
 * It is deliberately strict: both an amount with its unit (litres, or kWh) and a price must be
 * there, in either order. A phrase that only mentions one is not a fill-up, so an ordinary spoken
 * request never ends up in the fuel log by accident; and a figure outside what a tank or a battery
 * and a bill can hold is refused as a mis-heard number.
 */
object FuelVoiceParser {
    private const val NUMBER = """(\d+(?:,\d{3})*(?:\.\d+)?)"""

    private val LITERS = Regex("""$NUMBER\s*(?:ลิตร|ลิต|liters?|litres?|l\b|ล\.)""", RegexOption.IGNORE_CASE)
    private val KWH = Regex("""$NUMBER\s*(?:กิโลวัตต์ชั่วโมง|กิโลวัตต์|kwh|kw\b|หน่วย|units?)""", RegexOption.IGNORE_CASE)
    private val BAHT_AFTER = Regex("""$NUMBER\s*(?:บาท|baht|฿|thb)""", RegexOption.IGNORE_CASE)
    private val BAHT_BEFORE = Regex("""(?:ราคา|ยอด|เป็นเงิน|รวม|total|price|for)\s*$NUMBER""", RegexOption.IGNORE_CASE)

    private const val MAX_ENERGY = 200.0
    private const val MAX_BAHT = 100_000.0

    fun parse(input: String): FuelVoice? {
        val text = asciiDigits(input)
        val liters = LITERS.find(text)?.let { number(it.groupValues[1]) }
        val kwh = KWH.find(text)?.let { number(it.groupValues[1]) }
        // Both units in one phrase is not something to guess at.
        if ((liters == null) == (kwh == null)) return null
        val baht = (BAHT_AFTER.find(text) ?: BAHT_BEFORE.find(text))?.let { number(it.groupValues[1]) } ?: return null
        val amount = liters ?: kwh ?: return null
        if (amount <= 0 || amount > MAX_ENERGY || baht <= 0 || baht > MAX_BAHT) return null
        return FuelVoice(amount, baht, electric = kwh != null)
    }

    private fun number(raw: String): Double? = raw.replace(",", "").toDoubleOrNull()

    /** Thai digits (๐-๙) as the recognizer sometimes writes them, turned into 0-9. */
    private fun asciiDigits(text: String): String = buildString {
        for (c in text) append(if (c in '๐'..'๙') '0' + (c - '๐') else c)
    }
}
