package dev.autobridge.ui

import android.content.Context
import android.content.res.Configuration
import dev.autobridge.settings.AppearanceStore

/**
 * Applies Settings > Appearance > Text size to an activity's context.
 *
 * Every phone screen sizes its text in sp, so setting `fontScale` here resizes all of it, Views and
 * Compose alike, and on Android 14+ the platform applies its own non-linear curve (large text grows
 * less). "Follow Android font size" leaves the context untouched. Chain it after
 * [dev.autobridge.i18n.AppLocale.rebase] in `attachBaseContext`.
 */
object AppearanceContext {
    fun wrap(base: Context): Context {
        val spec = AppearanceStore.current()
        if (spec.followSystemText) return base
        val target = spec.textStep.scale
        if (base.resources.configuration.fontScale == target) return base
        val configuration = Configuration(base.resources.configuration).apply { fontScale = target }
        return base.createConfigurationContext(configuration)
    }
}
