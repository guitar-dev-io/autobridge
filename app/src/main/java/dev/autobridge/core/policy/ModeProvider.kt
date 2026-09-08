package dev.autobridge.core.policy

import dev.autobridge.BuildConfig
import dev.autobridge.core.model.AutoBridgeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Supplies the immutable product flavor mode to the rest of the application. */
interface ModeProvider {
    val mode: AutoBridgeMode
    val modeFlow: StateFlow<AutoBridgeMode>
}

class BuildModeProvider : ModeProvider {
    private val _modeFlow = MutableStateFlow(
        AutoBridgeMode.fromBuildValue(BuildConfig.AUTOBRIDGE_MODE)
    )

    override val mode: AutoBridgeMode get() = _modeFlow.value
    override val modeFlow: StateFlow<AutoBridgeMode> = _modeFlow.asStateFlow()
}

object DefaultModeProvider : ModeProvider by BuildModeProvider()
