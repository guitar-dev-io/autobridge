package dev.autobridge.ui

import android.app.AlertDialog
import android.content.Context
import android.view.View
import dev.autobridge.R
import dev.autobridge.design.AccentColor
import dev.autobridge.design.AppearanceSpec
import dev.autobridge.design.HomeLayout
import dev.autobridge.design.TextStep
import dev.autobridge.design.ThemeMode
import dev.autobridge.design.ThemePreset
import dev.autobridge.settings.AppearanceStore
import dev.autobridge.ui.AutoBridgeDesign.stack

/**
 * Settings > Appearance.
 *
 * First cut, so the whole chain (store, palette, tokens, font scale) runs end to end: theme mode,
 * preset, accent, text size and the home layout. The full page from the redesign (live preview,
 * wallpaper, icons and cards, fonts, the auto schedule) grows from here; see
 * `.agents/tasks/ui-redesign-v2/plan.md`, piece A.
 *
 * Every change goes through [AppearanceStore.update] and then [onChanged], which the phone shell
 * answers by recreating itself so everything already built picks the new look up.
 */
object AppearanceScreen {

    fun build(context: Context, onBack: () -> Unit, onChanged: () -> Unit): View {
        val spec = AppearanceStore.current()
        val change: ((AppearanceSpec) -> AppearanceSpec) -> Unit = { transform ->
            AppearanceStore.update(context, transform)
            onChanged()
        }
        val body = AutoBridgeDesign.body(context)

        body.stack(SettingsUi.group(context, context.getString(R.string.appearance_group_theme), listOf(
            SettingsUi.valueRow(context, context.getString(R.string.appearance_mode), modeLabel(context, spec.mode)) {
                choose(context, R.string.appearance_mode, ThemeMode.entries, spec.mode, { modeLabel(context, it) }) { picked ->
                    change { it.copy(mode = picked) }
                }
            },
            SettingsUi.valueRow(context, context.getString(R.string.appearance_preset), presetLabel(context, spec.preset)) {
                choose(context, R.string.appearance_preset, ThemePreset.entries, spec.preset, { presetLabel(context, it) }) { picked ->
                    // A preset brings its own accent, as the presets on the design canvas do; the
                    // accent row can still change it afterwards.
                    change { it.copy(preset = picked, accent = picked.suggested) }
                }
            },
            SettingsUi.valueRow(context, context.getString(R.string.appearance_accent), accentLabel(context, spec.accent)) {
                choose(context, R.string.appearance_accent, AccentColor.entries, spec.accent, { accentLabel(context, it) }) { picked ->
                    change { it.copy(accent = picked) }
                }
            }
        )), gap = 16)

        body.stack(SettingsUi.group(context, context.getString(R.string.appearance_group_text), listOf(
            SettingsUi.switchRow(
                context,
                context.getString(R.string.appearance_follow_system_text),
                context.getString(R.string.appearance_follow_system_text_caption),
                R.drawable.ic_tile_settings,
                AutoBridgeDesign.ACCENT,
                spec.followSystemText
            ) { change { it.copy(followSystemText = !it.followSystemText) } },
            SettingsUi.sliderRow(
                context,
                context.getString(R.string.appearance_text_size),
                steps = TextStep.entries.size - 1,
                progress = spec.textStep.ordinal,
                valueText = { stepLabel(context, TextStep.entries[it]) }
            ) { at -> change { it.copy(textStep = TextStep.entries[at], followSystemText = false) } }
        )), gap = 16)

        body.stack(SettingsUi.group(context, context.getString(R.string.appearance_group_home), listOf(
            SettingsUi.valueRow(context, context.getString(R.string.appearance_home_layout), layoutLabel(context, spec.homeLayout)) {
                choose(context, R.string.appearance_home_layout, HomeLayout.entries, spec.homeLayout, { layoutLabel(context, it) }) { picked ->
                    change { it.copy(homeLayout = picked) }
                }
            },
            SettingsUi.switchRow(
                context,
                context.getString(R.string.appearance_car_same_look),
                context.getString(R.string.appearance_car_same_look_caption),
                R.drawable.ic_tile_car,
                AutoBridgeDesign.ACCENT,
                spec.carSameLook
            ) { change { it.copy(carSameLook = !it.carSameLook) } }
        )), gap = 16)

        return AutoBridgeDesign.page(
            context = context,
            header = AutoBridgeDesign.header(
                context = context,
                title = context.getString(R.string.appearance_title),
                subtitle = context.getString(R.string.appearance_subtitle),
                onBack = onBack
            ),
            body = body,
            applyInsets = false
        )
    }

    /** One line for the Settings row: "Dark · AutoBridge · Sky". */
    fun summary(context: Context): String {
        val spec = AppearanceStore.current()
        return listOf(modeLabel(context, spec.mode), presetLabel(context, spec.preset), accentLabel(context, spec.accent))
            .joinToString(" · ")
    }

    private fun <T> choose(
        context: Context,
        title: Int,
        options: List<T>,
        selected: T,
        label: (T) -> String,
        onPick: (T) -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setSingleChoiceItems(options.map(label).toTypedArray(), options.indexOf(selected)) { dialog, which ->
                dialog.dismiss()
                if (options[which] != selected) onPick(options[which])
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    fun modeLabel(context: Context, mode: ThemeMode): String = context.getString(
        when (mode) {
            ThemeMode.SYSTEM -> R.string.appearance_mode_system
            ThemeMode.LIGHT -> R.string.appearance_mode_light
            ThemeMode.DARK -> R.string.appearance_mode_dark
            ThemeMode.AMOLED -> R.string.appearance_mode_amoled
            ThemeMode.AUTO -> R.string.appearance_mode_auto
        }
    )

    fun presetLabel(context: Context, preset: ThemePreset): String = context.getString(
        when (preset) {
            ThemePreset.AUTOBRIDGE -> R.string.appearance_preset_autobridge
            ThemePreset.GRAPHITE -> R.string.appearance_preset_graphite
            ThemePreset.OCEAN -> R.string.appearance_preset_ocean
            ThemePreset.EMBER -> R.string.appearance_preset_ember
            ThemePreset.DUSK -> R.string.appearance_preset_dusk
        }
    )

    fun accentLabel(context: Context, accent: AccentColor): String = context.getString(
        when (accent) {
            AccentColor.SKY -> R.string.appearance_accent_sky
            AccentColor.TEAL -> R.string.appearance_accent_teal
            AccentColor.AMBER -> R.string.appearance_accent_amber
            AccentColor.VIOLET -> R.string.appearance_accent_violet
            AccentColor.ROSE -> R.string.appearance_accent_rose
            AccentColor.LIME -> R.string.appearance_accent_lime
        }
    )

    fun stepLabel(context: Context, step: TextStep): String = context.getString(
        when (step) {
            TextStep.SMALL -> R.string.appearance_text_small
            TextStep.DEFAULT -> R.string.appearance_text_default
            TextStep.LARGE -> R.string.appearance_text_large
            TextStep.LARGER -> R.string.appearance_text_larger
            TextStep.HUGE -> R.string.appearance_text_huge
            TextStep.MAX -> R.string.appearance_text_max
        }
    ) + " · " + Math.round(step.scale * 100) + "%"

    fun layoutLabel(context: Context, layout: HomeLayout): String = context.getString(
        when (layout) {
            HomeLayout.CLASSIC -> R.string.appearance_home_classic
            HomeLayout.DASHBOARD -> R.string.appearance_home_dashboard
            HomeLayout.COMPACT -> R.string.appearance_home_compact
        }
    )
}
