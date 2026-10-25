package dev.autobridge.settings

import android.app.Activity
import android.app.Application
import android.os.Bundle
import dev.autobridge.R
import dev.autobridge.design.AccentColor
import dev.autobridge.design.ThemeHolder

/**
 * Puts the Appearance choice on each activity's Android theme, before its window exists.
 *
 * The pages draw themselves from [ThemeHolder.palette], but menus, popups, spinners, pickers,
 * switches and dialogs take their look from the activity's theme, which XML fixes as dark. So an
 * activity on [R.style.AppTheme] gets the light twin when the palette is light, and the user's
 * accent on top of either. One hook for every activity, instead of themed menus one call at a time.
 *
 * An activity with a theme of its own (the browser's two faces, the translucent share target, the
 * licences screen, car-facing surfaces) is left alone.
 */
object AppTheming : Application.ActivityLifecycleCallbacks {

    override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
        val onAppTheme = runCatching {
            activity.packageManager.getActivityInfo(activity.componentName, 0).themeResource == R.style.AppTheme
        }.getOrDefault(false)
        if (!onAppTheme) return
        AppearanceStore.refreshPalette(activity)
        val light = ThemeHolder.palette.isLight
        if (light) activity.setTheme(R.style.AppThemeLight)
        activity.theme.applyStyle(accentOverlay(ThemeHolder.spec.accent, light), true)
    }

    private fun accentOverlay(accent: AccentColor, light: Boolean): Int = when (accent) {
        AccentColor.SKY -> if (light) R.style.AccentOverlay_Sky_Light else R.style.AccentOverlay_Sky_Dark
        AccentColor.TEAL -> if (light) R.style.AccentOverlay_Teal_Light else R.style.AccentOverlay_Teal_Dark
        AccentColor.AMBER -> if (light) R.style.AccentOverlay_Amber_Light else R.style.AccentOverlay_Amber_Dark
        AccentColor.VIOLET -> if (light) R.style.AccentOverlay_Violet_Light else R.style.AccentOverlay_Violet_Dark
        AccentColor.ROSE -> if (light) R.style.AccentOverlay_Rose_Light else R.style.AccentOverlay_Rose_Dark
        AccentColor.LIME -> if (light) R.style.AccentOverlay_Lime_Light else R.style.AccentOverlay_Lime_Dark
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
