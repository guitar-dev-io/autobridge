package dev.autobridge.remote

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persistent Mobile Remote preferences (spec §19). Reactive so toggles apply immediately. */
object RemoteSettingsStore {
    private const val PREFS_NAME = "autobridge_remote_settings"

    data class Settings(
        val showConfirmation: Boolean = true,
        val hapticFeedback: Boolean = true,
        val autoSubmitText: Boolean = false,
        val rememberHistory: Boolean = true,
        val preferAgentForUnknown: Boolean = true,
        val resumeLastFeature: Boolean = false
    )

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val current: Settings get() = _settings.value

    fun restore(context: Context) {
        val p = prefs(context)
        _settings.value = Settings(
            showConfirmation = p.getBoolean("show_confirmation", true),
            hapticFeedback = p.getBoolean("haptic", true),
            autoSubmitText = p.getBoolean("auto_submit", false),
            rememberHistory = p.getBoolean("remember_history", true),
            preferAgentForUnknown = p.getBoolean("prefer_agent", true),
            resumeLastFeature = p.getBoolean("resume_last", false)
        )
    }

    fun update(context: Context, transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs(context).edit {
            putBoolean("show_confirmation", next.showConfirmation)
            putBoolean("haptic", next.hapticFeedback)
            putBoolean("auto_submit", next.autoSubmitText)
            putBoolean("remember_history", next.rememberHistory)
            putBoolean("prefer_agent", next.preferAgentForUnknown)
            putBoolean("resume_last", next.resumeLastFeature)
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
