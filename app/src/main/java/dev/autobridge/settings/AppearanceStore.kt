package dev.autobridge.settings

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.edit
import dev.autobridge.design.AppearanceCodec
import dev.autobridge.design.AppearanceSpec
import dev.autobridge.design.ThemeHolder
import java.io.File
import java.util.Calendar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings > Appearance, persisted. The one writer of [AppearanceSpec]: screens read [spec] (or
 * [current]) and change it through [update], and every change also refreshes [ThemeHolder], which
 * the design tokens read.
 *
 * Applying a change to what is already on screen is the caller's job (the phone shell recreates
 * its activity, the car home re-renders on its next frame).
 */
object AppearanceStore {
    private const val PREFS_NAME = "autobridge_appearance"

    private val _spec = MutableStateFlow(AppearanceSpec.DEFAULT)
    val spec: StateFlow<AppearanceSpec> = _spec.asStateFlow()

    fun current(): AppearanceSpec = _spec.value

    /** Loads the saved choices. Called from the Application before any screen draws. */
    fun restore(context: Context) {
        val stored = prefs(context).all.mapValues { it.value?.toString() }
        _spec.value = AppearanceCodec.decode(stored)
        refreshPalette(context)
    }

    fun update(context: Context, transform: (AppearanceSpec) -> AppearanceSpec) {
        val next = transform(_spec.value)
        if (next == _spec.value) return
        prefs(context).edit {
            clear()
            AppearanceCodec.encode(next).forEach { (key, value) -> putString(key, value) }
        }
        _spec.value = next
        refreshPalette(context)
    }

    fun reset(context: Context) = update(context) { AppearanceSpec.DEFAULT }

    /**
     * Re-resolves the palette against the system night mode and the clock. Call it when either may
     * have moved on (an activity resuming), since System and Auto depend on them.
     */
    fun refreshPalette(context: Context) {
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val now = Calendar.getInstance()
        ThemeHolder.update(
            _spec.value,
            systemDark = night != Configuration.UI_MODE_NIGHT_NO,
            minuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        )
    }

    /** Where the custom wallpaper photo is kept once picked (Wallpaper > Your photo). */
    fun customWallpaperFile(context: Context): File = File(context.filesDir, "appearance/wallpaper.jpg")

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
