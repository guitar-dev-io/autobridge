package dev.autobridge.core.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Presentation-only UI mode for AutoBridge.
 *
 * IMPORTANT: This is a "driving-aware UI mode", NOT a feature-restriction system. It only affects
 * how screens present themselves (larger controls, less clutter, prioritized actions). It never
 * gates, hides, locks, or disables any AutoBridge feature, and it never touches the platform
 * safety machinery (SpeedGate / ParkingStateStore / FeaturePolicy). Switching modes must never
 * destroy live feature state (browser page, media playback, mirror session, agent context).
 *
 * Platform-enforced restrictions (Android Auto / OS / vehicle head unit) are handled independently
 * by the existing safety stack and are always respected. This store adds nothing on top of them.
 */
object UiModeStore {
    enum class UiMode { NORMAL, DRIVING }

    private val _mode = MutableStateFlow(UiMode.NORMAL)
    val mode: StateFlow<UiMode> = _mode.asStateFlow()

    val isDriving: Boolean get() = _mode.value == UiMode.DRIVING

    fun setDriving(driving: Boolean) {
        val next = if (driving) UiMode.DRIVING else UiMode.NORMAL
        if (_mode.value != next) _mode.value = next
    }

    fun toggle() = setDriving(!isDriving)
}
