package dev.autobridge.i18n

import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.provider.Settings
import androidx.core.content.edit
import dev.autobridge.R
import java.util.Locale

/**
 * The language the UI is drawn in, and the one place that knows how to change it.
 *
 * Android has two different mechanisms for this and the one available depends on the API level,
 * which is why this exists rather than the call being made at each site:
 *
 *  - **API 33+** has per-app language as a platform feature. [LocaleManager] stores the choice
 *    outside the app, the system re-creates the activities itself, and the same choice shows up
 *    in Settings > Apps > AutoBridge > Language. `res/xml/locales_config.xml` is what populates
 *    that system picker, so a language added there needs no code change here.
 *  - **API 29–32** has nothing. The choice is kept in this app's own preferences and applied by
 *    [rebase] from `attachBaseContext`, the same hook [dev.autobridge.browser.CarDisplayScaling]
 *    uses for density.
 *
 * [selected] therefore reads whichever store is authoritative for the running platform, and
 * [select] writes it. The preference file is read on the old path only: letting it linger as a
 * second opinion on API 33+ is how an app ends up disagreeing with its own system settings page.
 *
 * Note what is deliberately *not* here: the voice-command vocabularies in
 * [dev.autobridge.agent.AgentCommandParser] and [dev.autobridge.remote.CommandParser] stay
 * bilingual in code. They match what the user *says*, which has nothing to do with the language
 * the UI happens to be drawn in — someone running the English UI still speaks Thai to the car.
 */
object AppLocale {

    private const val PREFS_NAME = "autobridge_locale"
    private const val KEY_TAG = "ui_language_tag"

    /** [select]/[selected] value meaning "no override, follow the device". */
    const val SYSTEM = ""

    /**
     * The languages the UI ships, in the order the picker offers them.
     *
     * Must stay in step with `res/values-<tag>`, `res/xml/locales_config.xml` and
     * `resourceConfigurations` in the app's build file — `scripts/check-i18n.sh` fails the build
     * when they drift. [labelRes] is an endonym (each language named in itself), which is why
     * those strings are marked untranslatable.
     */
    val OPTIONS: List<Option> = listOf(
        Option(SYSTEM, R.string.language_system),
        Option("en", R.string.language_en),
        Option("th", R.string.language_th),
    )

    /** One entry in [OPTIONS]: a BCP 47 tag, or [SYSTEM], and the name to show for it. */
    data class Option(val tag: String, val labelRes: Int)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The selected language tag, or [SYSTEM] when the device language is being followed. */
    fun selected(context: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)
                ?.applicationLocales
                ?.takeIf { !it.isEmpty }
                ?.get(0)
                ?.language
                .orEmpty()
        } else {
            prefs(context).getString(KEY_TAG, SYSTEM).orEmpty()
        }

    /** The [Option] currently in force; [OPTIONS] first entry when the tag is unrecognised. */
    fun selectedOption(context: Context): Option {
        val tag = selected(context)
        return OPTIONS.firstOrNull { it.tag == tag } ?: OPTIONS.first()
    }

    /** The option after the current one, wrapping — the in-app picker on API 29–32. */
    fun nextOption(context: Context): Option {
        val index = OPTIONS.indexOf(selectedOption(context))
        return OPTIONS[(index + 1) % OPTIONS.size]
    }

    /**
     * Stores [tag] as the UI language ([SYSTEM] clears the override).
     *
     * Returns true when the caller has to re-create its own UI. On API 33+ the system does that
     * for every activity and the answer is false; below it nothing is watching, so whoever
     * changed the setting has to restart itself.
     */
    fun select(context: Context, tag: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList()
                else LocaleList.forLanguageTags(tag)
            context.getSystemService(LocaleManager::class.java)?.applicationLocales = locales
            return false
        }
        prefs(context).edit { putString(KEY_TAG, tag) }
        return true
    }

    /**
     * [base] re-based on the stored language, or [base] itself when there is no override.
     *
     * Call from `attachBaseContext` so the activity and everything built from it — views, dialogs,
     * and any string a background helper reads off that context — agree on one language. A no-op
     * on API 33+, where the platform has already resolved the locale before the app sees the
     * context; running on top of that would only risk contradicting it.
     *
     * Also sets the JVM default locale, because the parts of the UI that format rather than
     * translate (dates, numbers) read [Locale.getDefault] and not the resources.
     */
    fun rebase(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = prefs(base).getString(KEY_TAG, SYSTEM).orEmpty()
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLocales(LocaleList(locale))
        }
        return base.createConfigurationContext(configuration)
    }

    /**
     * Opens the system per-app language screen, where Android 13+ offers every language
     * `locales_config.xml` declares. Returns false when that screen is unavailable — below API 33,
     * or on a build that ships no Settings activity for it — and the caller should fall back to
     * [nextOption].
     */
    fun openSystemPicker(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val intent = Intent(Settings.ACTION_APP_LOCALE_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
