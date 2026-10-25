package dev.autobridge.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import dev.autobridge.R
import dev.autobridge.design.FontChoice
import dev.autobridge.design.ThaiStyle
import dev.autobridge.design.ThemeHolder

/**
 * The app's typefaces, from Settings > Appearance > Text.
 *
 * Every screen that sets a typeface asks here ([regular], [medium], [bold], [weight]) instead of
 * naming a system family, so the font family and the Thai letterforms the user picked reach the
 * phone pages, the Compose pages and the car home alike.
 *
 * The families are variable fonts (res/font, SIL OFL; licences under assets/licenses), one file each:
 * a [Font] per weight is cut from it with the `wght` axis. A [Typeface] is then built with the Latin
 * family first, the Thai family second and the system's fallback last, so each script is drawn by
 * the font chosen for it. [FontChoice.SYSTEM] and [ThaiStyle.SYSTEM] leave that part to Android.
 *
 * Results are nullable only so JVM unit tests, where `android.graphics` is stubbed to return null,
 * can run code that sets a typeface.
 */
object AppFonts {
    /** Weights the app uses: text, medium labels, semi-bold titles, bold headings. */
    val WEIGHTS = intArrayOf(400, 500, 600, 700)

    @Volatile
    private var app: Context? = null
    /** Built typefaces by choice: the phone and the car may be on different ones. */
    private val built = HashMap<Pair<FontChoice, ThaiStyle>, Typeface?>()
    private val families = HashMap<Int, FontFamily>()

    /** Called once from the Application, before any screen builds. */
    fun init(context: Context) {
        app = context.applicationContext
    }

    fun regular(): Typeface? = weight(400)
    fun medium(): Typeface? = weight(500)
    fun bold(): Typeface? = weight(700)

    fun weight(weight: Int): Typeface? = Typeface.create(base(), weight, false)

    /** The typeface for the choices the user has made now. */
    @Synchronized
    fun base(): Typeface? {
        val spec = ThemeHolder.spec
        return forChoice(spec.font, spec.thai)
    }

    /**
     * The typeface for [font] and [thai], for drawing a sample of a choice before it is picked
     * (the font list on the Text page shows each family in its own letters).
     */
    @Synchronized
    fun forChoice(font: FontChoice, thai: ThaiStyle): Typeface? {
        val key = font to thai
        if (built[key] != null) return built[key]
        val typeface = build(font, thai)
        built[key] = typeface
        return typeface
    }

    /** A sample of one family at [weight], in its own Latin letters and the user's Thai. */
    fun sample(font: FontChoice, weight: Int = 500): Typeface? =
        Typeface.create(forChoice(font, ThemeHolder.spec.thai), weight, false)

    /** A sample of one Thai style at [weight], with the user's Latin family. */
    fun thaiSample(thai: ThaiStyle, weight: Int = 500): Typeface? =
        Typeface.create(forChoice(ThemeHolder.spec.font, thai), weight, false)

    private fun build(font: FontChoice, thai: ThaiStyle): Typeface? {
        val context = app ?: return Typeface.DEFAULT
        return runCatching {
            val chosen = listOfNotNull(latinFile(font), thaiFile(thai)).map { family(context, it) }
            if (chosen.isEmpty()) {
                Typeface.DEFAULT
            } else {
                val builder = Typeface.CustomFallbackBuilder(chosen.first())
                chosen.drop(1).forEach { builder.addCustomFallback(it) }
                builder.setSystemFallback("sans-serif").build()
            }
        }.getOrDefault(Typeface.DEFAULT)
    }

    /** All the weights of one variable font file as one family. */
    private fun family(context: Context, resId: Int): FontFamily = families.getOrPut(resId) {
        val fonts = WEIGHTS.map { w ->
            Font.Builder(context.resources, resId)
                .setWeight(w)
                .setFontVariationSettings("'wght' $w")
                .build()
        }
        FontFamily.Builder(fonts.first()).apply { fonts.drop(1).forEach { addFont(it) } }.build()
    }

    private fun latinFile(font: FontChoice): Int? = when (font) {
        FontChoice.INTER -> R.font.inter_var
        FontChoice.MANROPE -> R.font.manrope_var
        FontChoice.IBM_PLEX -> R.font.ibmplexsans_var
        FontChoice.NOTO -> R.font.notosans_var
        FontChoice.SYSTEM -> null
    }

    private fun thaiFile(thai: ThaiStyle): Int? = when (thai) {
        ThaiStyle.LOOPLESS -> R.font.notosansthai_var
        ThaiStyle.LOOPED -> R.font.notosansthailooped_var
        ThaiStyle.SYSTEM -> null
    }
}
